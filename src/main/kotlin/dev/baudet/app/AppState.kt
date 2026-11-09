package dev.baudet.app

import dev.baudet.api.BackendException
import dev.baudet.api.CancellationToken
import dev.baudet.api.ModelWindows
import dev.baudet.api.ResponsesClient
import dev.baudet.api.Turn
import dev.baudet.config.AppConfig
import dev.baudet.config.ContextStrategy
import dev.baudet.config.ToolMode
import dev.baudet.i18n.Key
import dev.baudet.i18n.Texts
import dev.baudet.project.FolderPermission
import dev.baudet.project.Project
import dev.baudet.session.Compactor
import dev.baudet.session.ContextTrimmer
import dev.baudet.session.Msg
import dev.baudet.session.SessionIds
import dev.baudet.session.SessionState
import dev.baudet.store.H2Store
import dev.baudet.store.SessionSummary
import dev.baudet.tool.CallExtractor
import dev.baudet.tool.CommandSignature
import dev.baudet.tool.EditFileTool
import dev.baudet.tool.ExecuteTool
import dev.baudet.tool.GitTool
import dev.baudet.tool.ReadFileTool
import dev.baudet.tool.LoopOutcome
import dev.baudet.tool.PromptedCallExtractor
import dev.baudet.tool.ResponsesTurnSource
import dev.baudet.tool.ToolContext
import dev.baudet.tool.ToolLoop
import dev.baudet.tool.ToolRegistry
import dev.baudet.tool.Transcript
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

    /**
     * The cancellation handle for the turn now in flight, or null while idle. [send] installs a
     * fresh token when it claims the lock and drops it when the turn ends; [cancelTurn] reaches
     * through it so a Stop closes the live socket even though it runs on the UI thread.
     */
    @Volatile private var activeToken: CancellationToken? = null

    /**
     * Ask the turn in flight to stop. It closes the token's live socket so a read blocked on the
     * backend unwinds at once; a blocking read does not respond to a thread interrupt, which is
     * why this is the authoritative stop. Safe to call while idle, when there is nothing to cut.
     */
    fun cancelTurn() {
        activeToken?.cancel()
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
     * Remember the height of the reasoning dock so a rebuild, such as a language change, and
     * the next launch reopen it where the user left it. A non-positive value means never
     * dragged, which lets the dock fall back to the input's own height. Like
     * [updateWindowGeometry] it rides in the same single-row JSON blob, so no schema is touched,
     * and it never fires [onConfigSaved], so storing the dock height cannot trigger a rebuild.
     */
    fun rememberReasoningDivider(px: Int) {
        if (px <= 0) return
        config = config.copy(reasoningDivider = px)
        store.save(config)
    }

    /**
     * Remember the height of the composer block under the transcript so a rebuild, such as a
     * language change, and the next launch reopen the split where the user left it. The height
     * is stored in pixels because it is independent of the window size, so growing the window
     * keeps the composer put. A non-positive value means never dragged, which lets the split
     * fall back to the composer's own height. Like [rememberReasoningDivider] it rides in the
     * same single-row JSON blob and never fires [onConfigSaved], so saving it cannot rebuild.
     */
    fun rememberComposerHeight(px: Int) {
        if (px <= 0) return
        config = config.copy(composerHeight = px)
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

    /**
     * The pinned commands as they stand right now. Read per tool call rather than once per turn,
     * and copied under the same lock [allowCommandAlways] writes under: the tool loop asks from
     * its worker thread while a click on the event thread can be adding to that very list, and
     * copying an ArrayList mid-add is how a permission becomes an exception.
     */
    fun allowedCommands(): Set<String> =
        synchronized(config.autoAllowCommands) { config.autoAllowCommands.toSet() }

    /**
     * Pin a command to the always-allow list and persist it, so the next matching command runs
     * without a prompt -- including the next call of the turn that is running now, since
     * [allowedCommands] is read afresh on every call. What is stored is [CommandSignature.pinned]:
     * a simple command pins to its signature so one decision covers its variations, while a chain
     * pins to its verbatim text so it is trusted exactly as approved. Persists with [H2Store.save]
     * alone rather than [saveConfig]: an allow-list change must not fire [onConfigSaved], which
     * would rebuild the panels for something the running turn reads from config on its next call.
     */
    fun allowCommandAlways(command: String) {
        val pinned = CommandSignature.pinned(command)
        synchronized(config.autoAllowCommands) {
            if (pinned in config.autoAllowCommands) return
            config.autoAllowCommands.add(pinned)
        }
        store.save(config)
    }

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

    /** The identity a learned window belongs to: a number learned off another server or model is stale. */
    private fun windowKey(): String = "${config.baseUrl}|${config.model}"

    /**
     * The window this model really has, or null when nobody told us. A number the backend refused
     * against beats the built-in table: a refusal is evidence about this exact server and model,
     * while the table is an estimate about a family. A learned number is trusted only under the key
     * it was learned on, so switching server or model drops it back to the table.
     */
    fun contextWindowTokens(): Int? {
        if (config.learnedContextWindow > 0 && config.learnedContextKey == windowKey())
            return config.learnedContextWindow
        return ModelWindows.known(config.model)
    }

    /** What Settings holds, or null when it is 0 or blank, which means "the app decides". */
    private fun typedBudgetTokens(): Int? = config.contextBudgetTokens.takeIf { it > 0 }

    /** A typed budget can only lower a ceiling, never raise it: it is a cap, not a floor. */
    private fun cappedByBudget(limit: Int): Int = typedBudgetTokens()?.let { minOf(it, limit) } ?: limit

    /**
     * The last ceiling: at or above this the turn is refused, because this is where the backend
     * would refuse it. The model's window less a margin, so a request cannot land exactly on the
     * window and still be turned down. Where no window is known this is simply what Settings holds,
     * and 0 there means off: nothing better may be invented.
     */
    private fun hardCeilingTokens(): Int {
        val window = contextWindowTokens() ?: return typedBudgetTokens() ?: 0
        return cappedByBudget((window - WINDOW_MARGIN_TOKENS).coerceAtLeast(MIN_CONTEXT_CEILING_TOKENS))
    }

    /**
     * The early ceiling, where folding starts. It sits well inside the window on purpose: the
     * conversation is measured as characters over three, and dense JSON -- which is what a tool
     * transcript mostly is -- costs more tokens per character than that estimate assumes, so a
     * ceiling near the window is passed in reality before it is passed on paper. Folding at three
     * quarters keeps that error inside the margin. A budget the user typed lowers it further.
     */
    private fun foldCeilingTokens(): Int {
        val window = contextWindowTokens() ?: return typedBudgetTokens() ?: 0
        val early = (window * FOLD_WINDOW_PERCENT / 100).coerceAtLeast(MIN_CONTEXT_CEILING_TOKENS)
        // Never later than the refusal it exists to prevent.
        return minOf(cappedByBudget(early), hardCeilingTokens())
    }

    /** Whether this conversation plus the reply it must leave room for reaches [ceiling]. */
    private fun over(ceiling: Int): Boolean {
        if (ceiling <= 0) return false
        val reserve = config.maxOutputTokens.coerceAtLeast(0)
        return session.estimateTokens() + reserve > ceiling
    }

    /**
     * The token budget the sent window is trimmed to: the early ceiling, shrunk by [shrinkPct] on a
     * retry so each attempt reaches for a smaller window than the one the backend just refused. It is
     * 0 when no window is known, which [ContextTrimmer] reads as leave the conversation alone.
     */
    private fun trimBudgetTokens(shrinkPct: Int): Long {
        // The rolling strategy is asked to give as much as the model can carry, so it fills toward a
        // larger slice of the window than the fold; keep-last-N is capped by its own count anyway and
        // keeps the conservative fold.
        val ceiling = if (config.contextStrategy == ContextStrategy.RollingWindow)
            rollingCeilingTokens() else foldCeilingTokens()
        return (ceiling * shrinkPct / 100).toLong()
    }

    /**
     * The ceiling the rolling window fills toward. Where the automatic fold stops at three quarters to
     * leave slack for the token estimate running loose on dense tool output, the rolling strategy
     * reaches for a larger slice of the window. It still sits inside the refusal edge, is lowered by a
     * typed budget, and stays behind the oversized-turn clamp and the shrink-on-refusal retry that
     * absorb any overshoot against a backend that counts differently than this estimate.
     */
    private fun rollingCeilingTokens(): Int {
        val window = contextWindowTokens() ?: return typedBudgetTokens() ?: 0
        val fill = (window * ROLLING_WINDOW_PERCENT / 100).coerceAtLeast(MIN_CONTEXT_CEILING_TOKENS)
        return minOf(cappedByBudget(fill), hardCeilingTokens())
    }

    /**
     * The copy of the conversation handed to the model for one turn. Under StopAtLimit this is the
     * whole transcript, because the guard already refused anything oversized before it could reach
     * here. Under the trimming strategies it is a bounded window; the stored transcript is never
     * edited, so what the user reads in the panel stays whole.
     */
    private fun outbound(s: SessionState, shrinkPct: Int): List<Msg> =
        ContextTrimmer.bound(
            s.promptHistory(), config.contextStrategy, trimBudgetTokens(shrinkPct),
            config.contextKeepRecentMessages
        )

    /** The server chain id to reuse for one turn, or null to replay from the client instead. Trimming
     *  always replays, because editing what we send cannot shrink a chain the backend already holds. */
    private fun chainedPrev(s: SessionState): String? =
        if (config.contextStrategy.supportsServerSession && config.useServerSession)
            s.previousResponseId else null

    /**
     * Record the window a refusal named, when it named one. Persisted at once: the number is worth
     * exactly as much after a restart as on the next turn, and losing it means failing again in the
     * same way. This never raises what the user set -- a typed budget only ever lowers a ceiling,
     * in [cappedByBudget].
     */
    fun learnContextWindow(claimed: Int?) {
        if (claimed == null || claimed <= 0) return
        if (config.learnedContextWindow == claimed && config.learnedContextKey == windowKey()) return
        config = config.copy(learnedContextWindow = claimed, learnedContextKey = windowKey())
        store.save(config)
    }

    /**
     * Why this turn must not go out as it stands, or null when it may. This refuses only under
     * [ContextStrategy.StopAtLimit]: the trimming strategies bound the window instead of refusing, so
     * they never reach a refusal here -- that silence is the whole point of making trimming the
     * default. Where it does refuse, it does so before the request rather than after the backend's
     * answer, so nothing is billed and the text the user typed stays in the input box. The message
     * names no token count on purpose: those counts belong in the status line, not in an error the
     * user has to read.
     */
    fun contextGuard(): String? {
        if (config.contextStrategy != ContextStrategy.StopAtLimit) return null
        val ceiling = hardCeilingTokens()
        if (ceiling <= 0) return null
        return if (over(ceiling)) Texts[Key.ContextTooLong] else null
    }

    /**
     * The token estimate a chained tool turn may reach before its next request is doomed. Under a
     * server session the backend replays and grows its own context, so the client cannot bound the
     * request by trimming what it sends; the loop watches this ceiling and stops cleanly instead of
     * firing a call the backend is certain to refuse. It mirrors [contextGuard]: the budget less the
     * reply it must leave room for, less a fixed allowance for the tool definitions and protocol the
     * loop prepends. 0 when no budget is configured, which disables the ceiling entirely.
     */
    private fun contextCeilingTokens(): Long {
        val budget = hardCeilingTokens()
        if (budget <= 0) return 0L
        val reserve = config.maxOutputTokens.coerceAtLeast(0) + INPUT_OVERHEAD_TOKENS
        return (budget - reserve).coerceAtLeast(MIN_CONTEXT_CEILING_TOKENS).toLong()
    }

    fun serverSessionActive(): Boolean =
        config.contextStrategy.supportsServerSession &&
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
        // Install the handle before the first request leaves, so a Stop pressed the instant the
        // turn begins still has a token to close; the same token is reused across overflow retries.
        val token = CancellationToken()
        activeToken = token
        val s = session
        s.messages.add(Msg("user", userText))
        val afterPrompt = s.messages.size
        var shrinkPct = 100
        var attempt = 0
        try {
            while (true) {
                try {
                    val turn = if (toolsActive(s)) runToolTurn(s, shrinkPct, token, onDelta, onReasoning)
                    else runPlainTurn(s, shrinkPct, token, onDelta, onReasoning)
                    persistTo(s)
                    return turn
                } catch (e: BackendException) {
                    // A trimming strategy turns a refusal into a smaller window rather than an error
                    // for the user: the refusal names the window the backend really has, so record it
                    // and send the same turn again against a window that shrinks each try. Only worth
                    // doing when nothing was appended yet -- a turn that already ran a real edit must
                    // not be replayed, and StopAtLimit refuses on purpose rather than retrying.
                    if (e.contextOverflow &&
                        config.contextStrategy != ContextStrategy.StopAtLimit &&
                        attempt < MAX_OVERFLOW_RETRIES && s.messages.size == afterPrompt
                    ) {
                        learnContextWindow(e.claimedContextTokens)
                        attempt++
                        shrinkPct = shrinkPct * OVERFLOW_SHRINK_STEP / 100
                        continue
                    }
                    throw e
                }
            }
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
            activeToken = null
            turnLock.set(false)
        }
    }

    /** The one-shot reply this app had before tools: one Responses call, then done. */
    private fun runPlainTurn(
        s: SessionState,
        shrinkPct: Int,
        token: CancellationToken,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit
    ): Turn {
        val prev = chainedPrev(s)
        // The prompt is the last stored message, so it is taken as the current input and only what
        // precedes it is replayed -- sent once, not twice, and scrubbed. Under a trimming strategy the
        // window was already bounded by outbound, so it is split without the fixed replay clamp that
        // would otherwise undo it; under StopAtLimit the transcript goes whole and the old clamp holds.
        val (prior, prompt) =
            if (config.contextStrategy == ContextStrategy.StopAtLimit) Transcript.forCall(outbound(s, shrinkPct))
            else Transcript.splitForWindow(outbound(s, shrinkPct))
        val turn = ResponsesClient(config).turn(
            system = instructionsFor(s),
            history = prior,
            userText = prompt,
            previousResponseId = prev,
            onDelta = onDelta,
            onReasoning = onReasoning,
            token = token
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
        shrinkPct: Int,
        token: CancellationToken,
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
            readGranted = readGranted,
            autoAllow = { allowedCommands() },
            autoApproveSafe = config.autoApproveSafeCommands
        )
        // Build the enabled tools once, then advertise exactly those in the prompt: the registry
        // and the system prompt must name the same set, or the model is taught to call a tool it
        // does not have, or kept blind to one it does. The wire format is model-facing, so it joins
        // the English system prompt only when tools are live; a plain or tool-less turn never sees it.
        val tools = buildList {
            add(ReadFileTool())
            if (writable) add(EditFileTool())
            if (executable) add(ExecuteTool())
            if (readGranted) add(GitTool())
        }
        val system = instructionsFor(s) + "\n\n" + CallExtractor.protocolFor(tools.map { it.name }.toSet())
        val loop = ToolLoop(
            // The source is given the same strategy and budget this turn opened with, so every
            // request the loop fires -- not only the first -- is capped to the configured budget
            // as tool observations accumulate.
            turnSource = ResponsesTurnSource(
                ResponsesClient(config), system, config.contextStrategy, trimBudgetTokens(shrinkPct),
                config.contextKeepRecentMessages, token = token
            ),

            registry = ToolRegistry(tools),
            extractor = PromptedCallExtractor(),
            maxIterations = config.maxToolIterations,
            chainResponseId = chainedPrev(s) != null,
            contextCeiling = contextCeilingTokens()
        )
        // The sink appends each assistant message and tool observation as the loop produces
        // them, so a later failure cannot discard a real edit already made on disk.
        val outcome = loop.run(
            history = outbound(s, shrinkPct),
            previousResponseId = chainedPrev(s),
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
                incompleteReason = outcome.incompleteReason
            )
        }
    }

    fun compact(keepLast: Int, onDelta: (String) -> Unit): String? {
        if (!turnLock.compareAndSet(false, true)) {
            throw IllegalStateException("a turn is already running")
        }
        try {
            // The window is handed in rather than looked up here, so the fold is guarded against the
            // same number the turn guard is: the one this backend proved, or the table's estimate for
            // this model. Two ceilings that disagree is how a fold overflows on a small model.
            val s = Compactor(config, contextWindowTokens()).compact(session, keepLast, onDelta)
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
        val budget = foldCeilingTokens()
        val trimming = config.contextStrategy != ContextStrategy.StopAtLimit
        // Under a trimming strategy the bar shows the size of the window actually sent, not the whole
        // transcript -- the transcript keeps growing, but that is harmless while a window is trimmed to
        // fit, so showing it would alarm without cause. StopAtLimit shows the whole conversation and
        // keeps nudging toward the manual Compact button, which is the only way forward there.
        val used = if (trimming) SessionState.estimateTokensOf(outbound(session, 100))
        else session.estimateTokens()
        val pct = if (budget <= 0) 0 else (used * 100 / budget)
        return if (trimming) "~${used} / $budget tokens (${pct}%)"
        else "~${used} / $budget tokens (${pct}%)" + if (pct > 80) Texts[Key.CompactRecommended] else ""
    }

    companion object {
        /** Fixed allowance for the tool definitions plus the protocol text a tool turn prepends. */
        private const val INPUT_OVERHEAD_TOKENS = 2000
        /** Floor so a small budget still lets the loop make at least one modest request. */
        private const val MIN_CONTEXT_CEILING_TOKENS = 1024
        /** Refusals to absorb before giving up and reporting: each retry aims at a smaller window. */
        private const val MAX_OVERFLOW_RETRIES = 2
        /** How much of the previous window a retry reaches for, as a percentage -- a shrinking bound. */
        private const val OVERFLOW_SHRINK_STEP = 60
        /**
         * Room held back between the model's stated window and what we treat as usable. A window is
         * the total the backend counts, and a request that lands exactly on it is still refused, so
         * the ceiling has to sit inside it by more than the reply alone.
         */
        private const val WINDOW_MARGIN_TOKENS = 4_096
        /** Where folding starts, as a percentage of the window; the refusal happens at its edge. */
        private const val FOLD_WINDOW_PERCENT = 75
        /** How much of the window the rolling strategy fills toward, seeking the model's maximum. */
        private const val ROLLING_WINDOW_PERCENT = 90

        /**
         * The data directory. Defaults to a per-user location that does not depend
         * on the working directory: a packaged macOS app is launched by Finder with
         * cwd=/, where a relative "baudet-data" would resolve to the read-only root.
         * Override with -Dbaudet.home for a checkout-local store.
         */
        fun dataDir(): Path {
            System.getProperty("baudet.home")?.let { return Paths.get(it) }
            val home = Paths.get(System.getProperty("user.home"))
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.contains("mac") ->
                    home.resolve("Library").resolve("Application Support").resolve("Baudet")
                os.contains("win") ->
                    System.getenv("APPDATA")?.let { Paths.get(it, "Baudet") } ?: home.resolve(".baudet")
                else -> System.getenv("XDG_DATA_HOME")?.let { Paths.get(it, "baudet") }
                    ?: home.resolve(".local").resolve("share").resolve("baudet")
            }
        }
        fun open(): AppState = AppState(H2Store(dataDir()))
    }
}
