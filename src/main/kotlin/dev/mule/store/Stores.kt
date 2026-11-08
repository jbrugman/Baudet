package dev.mule.store

import dev.mule.config.AppConfig
import dev.mule.session.Msg
import java.sql.Connection
import java.sql.DriverManager

interface SettingsStore {
    /** null on first run or an unreadable blob; config migration must never crash. */
    fun load(): AppConfig?
    fun save(cfg: AppConfig)
}

interface TranscriptStore {
    fun upsertSession(id: String, model: String?, prevResponseId: String?, lastSeq: Int)
    fun latestSessionId(limit: Int): List<String>
    fun messages(sessionId: String): List<Msg>
    fun previousResponseId(sessionId: String): String?
    fun model(sessionId: String): String?
    fun rewriteMessages(sessionId: String, msgs: List<Msg>)
}

/**
 * H2 over sqlite: sqlite-jdbc lays down platform-specific .dylib files, which
 * is guesswork in a native-image build, while H2 is pure Java and just carries
 * along. Persistent sessions: messages live in the DB so a restart restores the
 * conversation even once the server-side session is gone.
 */
class H2Store(dir: java.nio.file.Path) : SettingsStore, TranscriptStore {

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
                "PRIMARY KEY(session_id, seq))"
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

    override fun upsertSession(id: String, model: String?, prevResponseId: String?, lastSeq: Int) {
        db.prepareStatement(
            "MERGE INTO sessions(id, model, previous_response_id, last_seq, updated_at) " +
                "KEY(id) VALUES(?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, model)
            ps.setString(3, prevResponseId)
            ps.setInt(4, lastSeq)
            ps.setLong(5, System.currentTimeMillis())
            ps.executeUpdate()
        }
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
                    else ps.setLong(6, m.promptTokens!!)
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
}
