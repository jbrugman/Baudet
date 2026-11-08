package dev.mule.store

import dev.mule.config.AppConfig
import dev.mule.project.Project
import dev.mule.project.ProjectIds
import dev.mule.session.Msg
import java.sql.Connection
import java.sql.DriverManager

/**
 * H2 over sqlite: sqlite-jdbc lays down platform-specific .dylib files, which
 * is guesswork in a native-image build, while H2 is pure Java and just carries
 * along. Persistent sessions: messages live in the DB so a restart restores the
 * conversation even once the server-side session is gone.
 */
class H2Store(dir: java.nio.file.Path) : SettingsStore, TranscriptStore, ProjectStore {

    private val db: Connection

    init {
        java.nio.file.Files.createDirectories(dir)
        val url = "jdbc:h2:" + dir.resolve("mule").toAbsolutePath().toString() + ";DB_CLOSE_DELAY=-1"
        db = DriverManager.getConnection(url, "sa", "")
        listOf(
            "CREATE TABLE IF NOT EXISTS settings(id INT PRIMARY KEY, payload CLOB NOT NULL, updated_at BIGINT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS sessions(id VARCHAR(64) PRIMARY KEY, model VARCHAR(255), " +
                "previous_response_id VARCHAR(255), last_seq INT, updated_at BIGINT)",
            "CREATE TABLE IF NOT EXISTS messages(session_id VARCHAR(64), seq INT, role VARCHAR(32), " +
                "content CLOB, response_id VARCHAR(255), prompt_tokens BIGINT, " +
                "PRIMARY KEY(session_id, seq))",
            "CREATE TABLE IF NOT EXISTS projects(id VARCHAR(64) PRIMARY KEY, name VARCHAR(255) NOT NULL, " +
                "folder_path VARCHAR(1024), perms INT, archived BOOLEAN, created_at BIGINT)",
            // sessions gained a project column after the first release; the guard makes
            // the migration a no-op on a fresh database and idempotent on an old one.
            "ALTER TABLE sessions ADD COLUMN IF NOT EXISTS project_id VARCHAR(64)",
            // A custom title added after the first release; NULL keeps the derived title.
            "ALTER TABLE sessions ADD COLUMN IF NOT EXISTS title VARCHAR(255)",
            // The compaction summary added so it survives a restart; NULL when never compacted.
            "ALTER TABLE sessions ADD COLUMN IF NOT EXISTS summary CLOB"
        ).forEach { sql -> db.createStatement().use { it.execute(sql) } }
    }

    override fun load(): AppConfig? = try {
        db.prepareStatement("SELECT payload FROM settings WHERE id = 1").use { ps ->
            ps.executeQuery().use { rs ->
                if (rs.next()) dev.mule.api.json.decodeFromString(AppConfig.serializer(), rs.getString(1)) else null
            }
        }
    } catch (e: Exception) {
        System.err.println("could not read stored config, starting with defaults: ${e.message}")
        null
    }

    override fun save(cfg: AppConfig) {
        db.prepareStatement(
            "MERGE INTO settings(id, payload, updated_at) KEY(id) VALUES(1, ?, ?)"
        ).use { ps ->
            ps.setString(1, dev.mule.api.json.encodeToString(AppConfig.serializer(), cfg))
            ps.setLong(2, System.currentTimeMillis())
            ps.executeUpdate()
        }
    }

    override fun upsertSession(
        id: String,
        model: String?,
        prevResponseId: String?,
        lastSeq: Int,
        projectId: String?,
        summary: String?
    ) {
        db.prepareStatement(
            "MERGE INTO sessions(id, model, previous_response_id, last_seq, updated_at, project_id, summary) " +
                "KEY(id) VALUES(?, ?, ?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, model)
            ps.setString(3, prevResponseId)
            ps.setInt(4, lastSeq)
            ps.setLong(5, System.currentTimeMillis())
            ps.setString(6, projectId)
            ps.setString(7, summary)
            ps.executeUpdate()
        }
    }

    override fun summaryOf(sessionId: String): String? =
        db.prepareStatement("SELECT summary FROM sessions WHERE id = ?").use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    override fun latestSessionId(limit: Int): List<String> =
        db.prepareStatement("SELECT id FROM sessions ORDER BY updated_at DESC LIMIT $limit").use { ps ->
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }

    override fun messages(sessionId: String): List<Msg> =
        db.prepareStatement(
            "SELECT role, content, response_id, prompt_tokens FROM messages " +
                "WHERE session_id = ? ORDER BY seq"
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val tok = rs.getLong(4)
                        add(
                            Msg(
                                role = rs.getString(1),
                                content = rs.getString(2) ?: "",
                                responseId = rs.getString(3),
                                promptTokens = if (rs.wasNull()) null else tok
                            )
                        )
                    }
                }
            }
        }

    override fun previousResponseId(sessionId: String): String? =
        db.prepareStatement("SELECT previous_response_id FROM sessions WHERE id = ?").use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    override fun model(sessionId: String): String? =
        db.prepareStatement("SELECT model FROM sessions WHERE id = ?").use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    override fun rewriteMessages(sessionId: String, msgs: List<Msg>) {
        val auto = db.autoCommit
        db.autoCommit = false
        try {
            db.prepareStatement("DELETE FROM messages WHERE session_id = ?").use { ps ->
                ps.setString(1, sessionId)
                ps.executeUpdate()
            }
            db.prepareStatement(
                "INSERT INTO messages(session_id, seq, role, content, response_id, prompt_tokens) " +
                    "VALUES(?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                msgs.forEachIndexed { i, m ->
                    ps.setString(1, sessionId)
                    ps.setInt(2, i)
                    ps.setString(3, m.role)
                    ps.setString(4, m.content)
                    ps.setString(5, m.responseId)
                    if (m.promptTokens == null) ps.setNull(6, java.sql.Types.BIGINT)
                    else ps.setLong(6, m.promptTokens)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            db.commit()
        } catch (e: Exception) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = auto
        }
    }

    override fun deleteSession(id: String) {
        val auto = db.autoCommit
        db.autoCommit = false
        try {
            db.prepareStatement("DELETE FROM messages WHERE session_id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
            db.prepareStatement("DELETE FROM sessions WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
            db.commit()
        } catch (e: Exception) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = auto
        }
    }

    /** Rename one conversation; a blank title clears the override so the derived title returns. */
    override fun renameSession(id: String, title: String) {
        val trimmed = title.trim()
        db.prepareStatement("UPDATE sessions SET title = ? WHERE id = ?").use { ps ->
            if (trimmed.isEmpty()) ps.setNull(1, java.sql.Types.VARCHAR) else ps.setString(1, trimmed)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    // ---- projects --------------------------------------------------------

    private fun readProject(rs: java.sql.ResultSet) = Project(
        id = rs.getString("id"),
        name = rs.getString("name"),
        folderPath = rs.getString("folder_path"),
        permissions = rs.getInt("perms").let { if (rs.wasNull()) 0 else it },
        archived = rs.getBoolean("archived"),
        createdAt = rs.getLong("created_at")
    )

    override fun createProject(name: String): Project {
        val p = Project(id = ProjectIds.new(), name = name.trim(), createdAt = System.currentTimeMillis())
        db.prepareStatement(
            "INSERT INTO projects(id, name, folder_path, perms, archived, created_at) VALUES(?, ?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, p.id)
            ps.setString(2, p.name)
            ps.setString(3, p.folderPath)
            ps.setInt(4, p.permissions)
            ps.setBoolean(5, p.archived)
            ps.setLong(6, p.createdAt)
            ps.executeUpdate()
        }
        return p
    }

    /** Active projects first, then archived; within a group, oldest first. */
    override fun listProjects(): List<Project> =
        db.prepareStatement(
            "SELECT id, name, folder_path, perms, archived, created_at FROM projects " +
                "ORDER BY archived, created_at"
        ).use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(readProject(rs)) } }
        }

    override fun renameProject(id: String, name: String) {
        db.prepareStatement("UPDATE projects SET name = ? WHERE id = ?").use { ps ->
            ps.setString(1, name.trim())
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    override fun setFolder(id: String, path: String?, perms: Int) {
        db.prepareStatement("UPDATE projects SET folder_path = ?, perms = ? WHERE id = ?").use { ps ->
            ps.setString(1, path)
            ps.setInt(2, perms)
            ps.setString(3, id)
            ps.executeUpdate()
        }
    }

    override fun setArchived(id: String, archived: Boolean) {
        db.prepareStatement("UPDATE projects SET archived = ? WHERE id = ?").use { ps ->
            ps.setBoolean(1, archived)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    override fun deleteProject(id: String) {
        val auto = db.autoCommit
        db.autoCommit = false
        try {
            db.prepareStatement(
                "DELETE FROM messages WHERE session_id IN (SELECT id FROM sessions WHERE project_id = ?)"
            ).use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
            db.prepareStatement("DELETE FROM sessions WHERE project_id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
            db.prepareStatement("DELETE FROM projects WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
            db.commit()
        } catch (e: Exception) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = auto
        }
    }

    /**
     * The session title is the first line of its first user message, falling back to
     * the id when a session has no user turn yet, so an empty new chat is still named.
     */
    override fun sessionsIn(projectId: String?): List<SessionSummary> {
        val where = if (projectId == null) "s.project_id IS NULL" else "s.project_id = ?"
        return db.prepareStatement(
            "SELECT s.id, s.last_seq, s.updated_at, s.title, " +
                "(SELECT m.content FROM messages m WHERE m.session_id = s.id AND m.role = 'user' " +
                "ORDER BY m.seq LIMIT 1) " +
                "FROM sessions s WHERE $where ORDER BY s.updated_at DESC"
        ).use { ps ->
            if (projectId != null) ps.setString(1, projectId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val id = rs.getString(1)
                        // A stored title wins; when none is set the first user line is the name.
                        val custom = rs.getString(4)?.trim()
                        val title = if (custom.isNullOrEmpty()) sessionTitle(rs.getString(5), id) else custom
                        add(SessionSummary(id, title, rs.getInt(2), rs.getLong(3)))
                    }
                }
            }
        }
    }

    private fun sessionTitle(firstUser: String?, id: String): String {
        val line = firstUser?.trim()?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
        if (line.isNullOrEmpty()) return id
        return if (line.length <= 48) line else line.substring(0, 47) + "…"
    }

    override fun projectOfSession(sessionId: String): String? =
        db.prepareStatement("SELECT project_id FROM sessions WHERE id = ?").use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
}
