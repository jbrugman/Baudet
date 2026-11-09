package dev.mule.app

import dev.mule.api.ResponsesClient
import dev.mule.api.Turn
import dev.mule.config.AppConfig
import dev.mule.config.ToolMode
import dev.mule.i18n.Key
import dev.mule.i18n.Texts
import dev.mule.project.FolderPermission
import dev.mule.project.Project
import dev.mule.session.Compactor
import dev.mule.session.Msg
import dev.mule.session.SessionIds
import dev.mule.session.SessionState
import dev.mule.store.H2Store
import dev.mule.store.SessionSummary
import dev.mule.tool.CallExtractor
import dev.mule.tool.EditFileTool
import dev.mule.tool.ExecuteTool
import dev.mule.tool.GitTool
import dev.mule.tool.ReadFileTool
import dev.mule.tool.LoopOutcome
import dev.mule.tool.PromptedCallExtractor
import dev.mule.tool.ResponsesTurnSource
import dev.mule.tool.ToolContext
import dev.mule.tool.ToolLoop
import dev.mule.tool.ToolRegistry
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The single place that ties config, session and persistence together.
 * Everything here blocks: the UI must call it from a worker thread.
 */
class AppState(val store: H2Store) {

    var config: AppConfig = store.load() ?: AppConfig()
    var session: SessionState = SessionState(SessionIds.new(), config.model, null)

    /**
     * True while a turn runs against the session it captured. Session switchers refuse to
     * move the field while this holds, so a reply never lands in a conversation the user
     * switched to mid-stream. Backed by an AtomicBoolean so [send] and [compact] claim it
     * atomically and a second concurrent turn is rejected; the setter stays public so a test
     * can hold the flag, while the UI leaves the actual claim to those two methods.
     */
    private val turnLock = java.util.concurrent.atomic.AtomicBoolean(false)

    var turning: Boolean
        get() = turnLock.get()
        set(value) {
            turnLock.set(value)
        }

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
        session.summary = store.summaryOf(id)
        session.projectId = store.projectOfSession(id)
    }

    /**
     * Notified after a config is persisted. The UI uses it for the one change it
     * cannot absorb in place: the language, which needs the panels rebuilt.
     */
    var onConfigSaved: (AppConfig) -> Unit = {}

    /**
     * How a proposed file change gets approval in Ask mode. The UI installs a
     * dialog here; default deny, so an unattended loop never writes on its own.
     * Auto mode bypasses it, and the tool never calls it when tools are off.
     */
    var approveEdit: (relativePath: String, diff: String) -> Boolean = { _, _ -> false }

    /**
     * The run equivalent of [approveEdit]. A command is shown for confirmation and, unlike
     * an edit, it stays required even in Auto mode: a subprocess cannot be fenced by the
     * folder boundary, so the human is the only real guard. Default deny, so an unattended
     * loop never runs a command on its own.
     */
    var approveCommand: (command: String, workingDir: String, timeoutSeconds: Int) -> Boolean =
        { _, _, _ -> false }

    fun saveConfig(next: AppConfig) {
        config = next
        store.save(next)
        session.model = next.model
        Texts.language = Texts.parse(next.language)
        onConfigSaved(next)
    }

    /**
     * Persist only the window geometry and the three remembered split widths. Unlike
     * [saveConfig] this never fires [onConfigSaved], so storing layout when the window
     * closes cannot trigger a panel rebuild or a feedback loop.
     */
    fun updateWindowGeometry(x: Int, y: Int, w: Int, h: Int, left: Int, right: Int, files: Int) {
        config = config.copy(
            windowX = x,
            windowY = y,
            windowWidth = w,
            windowHeight = h,
            leftDivider = left,
            rightDivider = right,
            filesDivider = files
        )
        store.save(config)
    }

    /**
     * Remember where and how big the user left the diff viewer, so the next patch reopens
     * there instead of re-centering at the small default. Like [updateWindowGeometry] it
     * rides in the same single-row JSON blob, so no schema is touched.
     */
    fun updateDiffPopupBounds(x: Int, y: Int, width: Int, height: Int) {
        config = config.copy(
            diffPopupX = x, diffPopupY = y,
            diffPopupWidth = width, diffPopupHeight = height
        )
        store.save(config)
    }

    /** Start a new conversation in the currently selected project, if any. */
    fun newSession(): SessionState = newSession(currentProjectId)

    /** Start a new conversation under a specific project; null places it in Unassigned. */
    fun newSession(projectId: String?): SessionState {
        if (turning) return session
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
        if (turning) return session
        session = SessionState(SessionIds.new(), config.model, null)
        session.projectId = projectId
        return session
    }

    /**
     * Load an existing conversation by id and adopt its project, so a later
     * New session continues in the same place as the one just opened.
     */
    fun loadSession(id: String) {
        if (turning) return
        session = SessionState(
            sessionId = id,
            model = store.model(id) ?: config.model,
            previousResponseId = store.previousResponseId(id),
            messages = store.messages(id).toMutableList()
        )
        session.summary = store.summaryOf(id)
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
        if (turning) return
        val openWasInside = session.projectId == id
        store.deleteProject(id)
        if (currentProjectId == id) currentProjectId = null
        // The open conversation lived here and is gone too; adopt a fresh session so a
        // later persist cannot resurrect the deleted rows.
        if (openWasInside) session = SessionState(SessionIds.new(), config.model, null)
    }

    /** Delete one conversation; if it is the open one, a fresh conversation takes its place. */
    fun deleteSession(id: String) {
        if (turning) return
        val openWasIt = session.sessionId == id
        store.deleteSession(id)
        if (openWasIt) session = SessionState(SessionIds.new(), config.model, null)
    }

    /** Rename one conversation; a blank title reverts to the derived first-line title. */
    fun renameSession(id: String, title: String) = store.renameSession(id, title)

    fun serverSessionActive(): Boolean =
        config.useServerSession && session.previousResponseId != null

    /** The project the given conversation belongs to, or null when it is Unassigned. */
    private fun currentProject(s: SessionState): Project? =
        s.projectId?.let { id -> projects().firstOrNull { it.id == id } }

    /**
     * Why this turn can or cannot enter the tool loop, as one reason in a fixed
     * precedence. Only [ToolGate.Active] sends a turn through the loop: the user must
     * have tools on *and* the conversation's project must expose a linked folder with
     * the Write right. The reasons are exposed, not collapsed to a boolean, so the UI
     * can tell the user what to fix (link a folder, grant Write) instead of letting a
     * request to edit fall silently onto the plain path.
     */
    fun toolGate(): ToolGate = toolGate(session)

    /** Same gate, evaluated against one session so a running turn reads the one it began with. */
    private fun toolGate(s: SessionState): ToolGate {
        if (config.toolMode == ToolMode.Off) return ToolGate.Disabled
        val project = currentProject(s) ?: return ToolGate.NoProject
        if (project.folderPath.isNullOrBlank()) return ToolGate.NoFolder
        if (!project.isGranted(FolderPermission.Write) && !project.isGranted(FolderPermission.Execute))
            return ToolGate.NoWrite
        return ToolGate.Active
    }

    /**
     * System instructions for one session: the resolved prompt with the compaction summary
     * folded in. The summary rides here rather than as a replayed system message because
     * the request builder drops system messages, which would silently lose it after a
     * compaction. Model-facing, so English whatever the UI language is.
     */
    private fun instructionsFor(s: SessionState): String {
        val summary = s.summary
        val base = config.resolvedSystemPrompt()
        return if (summary == null) base
        else base + "\n\nSummary of the earlier conversation:\n" + summary
    }

    /** Whether a turn enters the loop; true exactly when [toolGate] is [ToolGate.Active]. */
    fun toolsActive(): Boolean = toolGate() == ToolGate.Active

    private fun toolsActive(s: SessionState): Boolean = toolGate(s) == ToolGate.Active

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
        if (!turnLock.compareAndSet(false, true)) {
            throw IllegalStateException("a turn is already running")
        }
        val s = session
        s.messages.add(Msg("user", userText))
        val afterPrompt = s.messages.size
        try {
            val turn = if (toolsActive(s)) runToolTurn(s, onDelta, onReasoning)
            else runPlainTurn(s, userText, onDelta, onReasoning)
            persistTo(s)
            return turn
        } catch (e: Exception) {
            // The plain path lands nothing on a failure, so the only new message is the
            // prompt: drop it and keep the conversation clean. The tool path may already
            // have appended real actions through the sink; those stay, because losing a
            // recorded edit whose file changed on disk is worse than an unanswered turn.
            if (s.messages.size == afterPrompt) s.messages.removeAt(s.messages.lastIndex)
            else {
                // Real actions already ran and are retained, but the server session was never
                // told about them: its response id predates this turn. Drop the id so the next
                // turn replays the whole transcript client-side and cannot miss the edit.
                s.previousResponseId = null
                persistTo(s)
            }
            throw e
        } finally {
            turnLock.set(false)
        }
    }

    /** The one-shot reply this app had before tools: one Responses call, then done. */
    private fun runPlainTurn(
        s: SessionState,
        userText: String,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit
    ): Turn {
        val prev = if (config.useServerSession) s.previousResponseId else null
        // The prompt is already the last stored message; drop it from the replayed history
        // so the client sends it exactly once, as userText, instead of twice.
        val turn = ResponsesClient(config).turn(
            system = instructionsFor(s),
            history = s.promptHistory().dropLast(1),
            userText = userText,
            previousResponseId = prev,
            onDelta = onDelta,
            onReasoning = onReasoning
        )
        s.messages.add(Msg("assistant", turn.text, turn.responseId, turn.usage?.prompt))
        if (turn.responseId != null) s.previousResponseId = turn.responseId
        return turn
    }

    /**
     * The tool-loop path. It replays the running transcript (the just-added prompt is
     * last) and the loop feeds each tool observation back until the model stops asking
     * or [AppConfig.maxToolIterations] cuts it off. Only the messages the loop appended
     * fold into the session, so a failure before any write leaves the session holding
     * just the prompt, which the caller's catch then drops.
     */
    private fun runToolTurn(
        s: SessionState,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit
    ): Turn {
        val project = currentProject(s)
            ?: error("runToolTurn invoked without a gated project") // toolsActive() has gated this
        val writable = project.isGranted(FolderPermission.Write)
        val executable = project.isGranted(FolderPermission.Execute)
        val readGranted = project.isGranted(FolderPermission.Read)
        val context = ToolContext(
            root = Paths.get(project.folderPath!!),
            writeGranted = writable,
            approve = if (config.toolMode == ToolMode.Auto) { _, _ -> true } else approveEdit,
            execGranted = executable,
            approveCommand = approveCommand,
            readGranted = readGranted
        )
        // The wire format is model-facing, so it joins the English system prompt only
        // when tools are live; a plain or tool-less turn never mentions it.
        val system = instructionsFor(s) + "\n\n" + CallExtractor.PROTOCOL
        val loop = ToolLoop(
            turnSource = ResponsesTurnSource(ResponsesClient(config), system),
            registry = ToolRegistry(buildList {
                add(ReadFileTool())
                if (writable) add(EditFileTool())
                if (executable) add(ExecuteTool())
                if (readGranted) add(GitTool())
            }),
            extractor = PromptedCallExtractor(),
            maxIterations = config.maxToolIterations,
            chainResponseId = config.useServerSession && s.previousResponseId != null
        )
        // The sink appends each assistant message and tool observation as the loop produces
        // them, so a later failure cannot discard a real edit already made on disk.
        val outcome = loop.run(
            history = s.promptHistory(),
            previousResponseId = s.previousResponseId,
            ctx = context,
            onDelta = onDelta,
            onReasoning = onReasoning,
            onMessage = { s.messages.add(it) }
        )
        if (outcome.responseId != null) s.previousResponseId = outcome.responseId
        return when (outcome) {
            is LoopOutcome.Completed ->
                if (outcome.capReached && outcome.finalTurn.incompleteReason == null)
                    outcome.finalTurn.copy(incompleteReason = "stopped_after_tool_cap")
                else outcome.finalTurn
            is LoopOutcome.CapReached -> Turn(
                text = "",
                responseId = outcome.responseId,
                usage = null,
                finalEvent = null,
                incompleteReason = "max_tool_iterations"
            )
        }
    }

    fun compact(keepLast: Int, onDelta: (String) -> Unit): String? {
        if (!turnLock.compareAndSet(false, true)) {
            throw IllegalStateException("a turn is already running")
        }
        try {
            val s = Compactor(config).compact(session, keepLast, onDelta)
            persist()
            return s
        } finally {
            turnLock.set(false)
        }
    }

    fun persist() = persistTo(session)

    /**
     * Persist one conversation. The summary is written alongside the turns so it survives a
     * restart: the older turns are already gone after a compaction, so losing only the
     * summary would drop that context for good.
     */
    private fun persistTo(s: SessionState) {
        store.rewriteMessages(s.sessionId, s.messages)
        store.upsertSession(
            s.sessionId, s.model, s.previousResponseId, s.messages.size, s.projectId, s.summary
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
