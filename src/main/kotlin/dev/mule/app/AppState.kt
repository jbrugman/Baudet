package dev.mule.app

import dev.mule.api.ResponsesClient
import dev.mule.api.Turn
import dev.mule.config.AppConfig
import dev.mule.i18n.Key
import dev.mule.i18n.Texts
import dev.mule.project.Project
import dev.mule.session.Compactor
import dev.mule.session.Msg
import dev.mule.session.SessionIds
import dev.mule.session.SessionState
import dev.mule.store.H2Store
import dev.mule.store.SessionSummary
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The single place that ties config, session and persistence together.
 * Everything here blocks: the UI must call it from a worker thread.
 */
class AppState(val store: H2Store) {

    var config: AppConfig = store.load() ?: AppConfig()
    var session: SessionState = SessionState(SessionIds.new(), config.model, null)

    /** The project a new chat is created under; set when one is selected in the sidebar. */
    var currentProjectId: String? = null

    init {
        Texts.language = Texts.parse(config.language)
        restoreLatest()
    }

    /** Loads the most recent conversation so context survives a restart. */
    private fun restoreLatest() {
        val id = store.latestSessionId(1).firstOrNull() ?: return
        val msgs = store.messages(id)
        if (msgs.isEmpty()) return
        session = SessionState(
            sessionId = id,
            model = store.model(id) ?: config.model,
            previousResponseId = store.previousResponseId(id),
            messages = msgs.toMutableList()
        )
        session.projectId = store.projectOfSession(id)
    }

    /**
     * Notified after a config is persisted. The UI uses it for the one change it
     * cannot absorb in place: the language, which needs the panels rebuilt.
     */
    var onConfigSaved: (AppConfig) -> Unit = {}

    fun saveConfig(next: AppConfig) {
        config = next
        store.save(next)
        session.model = next.model
        Texts.language = Texts.parse(next.language)
        onConfigSaved(next)
    }

    /** Start a new conversation in the currently selected project, if any. */
    fun newSession(): SessionState = newSession(currentProjectId)

    /** Start a new conversation under a specific project; null places it in Unassigned. */
    fun newSession(projectId: String?): SessionState {
        session = SessionState(SessionIds.new(), config.model, null)
        session.projectId = projectId
        store.upsertSession(session.sessionId, session.model, null, 0, projectId)
        return session
    }

    /**
     * Begin a conversation under a project without writing an empty row: the first
     * message persists it. Choosing a project uses this, so clicking through projects
     * never litters the tree with abandoned empty sessions.
     */
    fun startSessionIn(projectId: String?): SessionState {
        session = SessionState(SessionIds.new(), config.model, null)
        session.projectId = projectId
        return session
    }

    /**
     * Load an existing conversation by id and adopt its project, so a later
     * New session continues in the same place as the one just opened.
     */
    fun loadSession(id: String) {
        session = SessionState(
            sessionId = id,
            model = store.model(id) ?: config.model,
            previousResponseId = store.previousResponseId(id),
            messages = store.messages(id).toMutableList()
        )
        session.projectId = store.projectOfSession(id)
        currentProjectId = session.projectId
    }

    // ---- projects --------------------------------------------------------

    fun projects(): List<Project> = store.listProjects()
    fun sessionsIn(projectId: String?): List<SessionSummary> = store.sessionsIn(projectId)
    fun addProject(name: String): Project = store.createProject(name)
    fun renameProject(id: String, name: String) = store.renameProject(id, name)
    fun linkFolder(id: String, path: String?, perms: Int) = store.setFolder(id, path, perms)
    fun setProjectArchived(id: String, archived: Boolean) = store.setArchived(id, archived)

    /** Delete a project and every conversation inside it; files on disk are never touched. */
    fun deleteProject(id: String) {
        val openWasInside = session.projectId == id
        store.deleteProject(id)
        if (currentProjectId == id) currentProjectId = null
        // The open conversation lived here and is gone too; adopt a fresh session so a
        // later persist cannot resurrect the deleted rows.
        if (openWasInside) session = SessionState(SessionIds.new(), config.model, null)
    }

    /** Delete one conversation; if it is the open one, a fresh conversation takes its place. */
    fun deleteSession(id: String) {
        val openWasIt = session.sessionId == id
        store.deleteSession(id)
        if (openWasIt) session = SessionState(SessionIds.new(), config.model, null)
    }

    /** Rename one conversation; a blank title reverts to the derived first-line title. */
    fun renameSession(id: String, title: String) = store.renameSession(id, title)

    fun serverSessionActive(): Boolean =
        config.useServerSession && session.previousResponseId != null

    /**
     * Blocking. Call off the EDT; both callbacks fire on that worker thread.
     *
     * Reasoning is display-only and deliberately never stored in a Msg: the backend
     * keeps it behind the response id we send back, so storing it client-side too only
     * inflates every later turn.
     */
    fun send(
        userText: String,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit = {}
    ): Turn {
        session.messages.add(Msg("user", userText))
        val prev = if (config.useServerSession) session.previousResponseId else null
        val turn: Turn
        try {
            turn = ResponsesClient(config).turn(
                system = config.resolvedSystemPrompt(),
                history = session.promptHistory(),
                userText = userText,
                previousResponseId = prev,
                onDelta = onDelta,
                onReasoning = onReasoning
            )
        } catch (e: Exception) {
            session.messages.removeAt(session.messages.lastIndex) // do not keep a half-finished turn
            throw e
        }
        session.messages.add(Msg("assistant", turn.text, turn.responseId, turn.usage?.prompt))
        if (turn.responseId != null) session.previousResponseId = turn.responseId
        persist()
        return turn
    }

    fun compact(keepLast: Int, onDelta: (String) -> Unit): String? {
        val s = Compactor(config).compact(session, keepLast, onDelta)
        persist()
        return s
    }

    fun persist() {
        store.rewriteMessages(session.sessionId, session.messages)
        store.upsertSession(
            session.sessionId, session.model, session.previousResponseId, session.messages.size, session.projectId
        )
    }

    fun contextWarning(): String {
        val used = session.estimateTokens()
        val budget = config.contextBudgetTokens
        val pct = if (budget <= 0) 0 else (used * 100 / budget)
        return "~${used} / $budget tokens (${pct}%)" +
            if (pct > 80) Texts[Key.CompactRecommended] else ""
    }

    companion object {
        /**
         * The data directory. Defaults to a per-user location that does not depend
         * on the working directory: a packaged macOS app is launched by Finder with
         * cwd=/, where a relative "mule-data" would resolve to the read-only root.
         * Override with -Dmule.home for a checkout-local store.
         */
        fun dataDir(): Path {
            System.getProperty("mule.home")?.let { return Paths.get(it) }
            val home = Paths.get(System.getProperty("user.home"))
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.contains("mac") ->
                    home.resolve("Library").resolve("Application Support").resolve("Mule")
                os.contains("win") ->
                    System.getenv("APPDATA")?.let { Paths.get(it, "Mule") } ?: home.resolve(".mule")
                else -> System.getenv("XDG_DATA_HOME")?.let { Paths.get(it, "mule") }
                    ?: home.resolve(".local").resolve("share").resolve("mule")
            }
        }
        fun open(): AppState = AppState(H2Store(dataDir()))
    }
}
