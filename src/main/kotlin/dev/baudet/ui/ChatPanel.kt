package dev.baudet.ui

import dev.baudet.api.BackendException
import dev.baudet.api.Turn
import dev.baudet.app.AppState
import dev.baudet.app.ToolGate
import dev.baudet.i18n.Key
import dev.baudet.i18n.Texts
import dev.baudet.io.ImageClipboard
import dev.baudet.session.CompactionOutcome
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Image
import java.awt.Insets
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.image.BufferedImage
import java.util.concurrent.Executors
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.JTextPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.text.DefaultStyledDocument
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.border.EmptyBorder

/**
 * Transcript plus input. The stream is written straight into the document
 * layer; the status line shows response_id and the token counts, because those
 * are the two numbers that reveal whether the server-side session and the
 * prefix cache are actually working.
 *
 * Reasoning never enters the transcript. It fills a closable dock above the input instead, so a
 * thinking model can be as verbose as it likes without pushing the conversation away, and it is
 * still never stored: the dock is display-only and empties on the next redraw, which keeps it
 * out of the context sent back to the model.
 */
class ChatPanel(private val state: AppState) : JPanel(BorderLayout()) {

    companion object {
        private const val SEND_COMMAND = "baudet-send"
        private const val PASTE_COMMAND = "baudet-paste-image"

        /** How far above the bottom still counts as resting at the tail, in pixels. */
        private const val TAIL_SLACK = 24
        private const val COLLAPSE_ARROW = "\u25be"
        private const val EXPAND_ARROW = "\u25b8"
    }

    /**
     * The transcript: one card per step the model took, filled by the events of the turn. This
     * replaced one flat text document, which could colour a run of characters but could not hold
     * a component -- so a command the reader's own machine ran, and the write that followed it,
     * had nowhere to be drawn and the turn read as prose only.
     */
    private val transcript = TranscriptView().apply {
        onApproval = { id, decision -> state.resolveApproval(id, decision) }
    }

    private val input = JTextArea(4, 60).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        lineWrap = true
        wrapStyleWord = true
    }
    private val sendButton = JButton(Texts[Key.SendButton])
    private val stopButton = JButton(Texts[Key.StopButton]).apply {
        isEnabled = false
        addActionListener { stop() }
    }
    private val newSessionButton = JButton(Texts[Key.NewSessionButton]).apply { addActionListener { newSession() } }
    /** The staged image as a data URL for the current turn only; it rides the request but is not stored. */
    private var pendingImageDataUrl: String? = null
    private val attachmentLabel = JLabel()
    private val attachmentStrip = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
        isVisible = false
        add(attachmentLabel)
        add(JButton(Texts[Key.ImageRemoveButton]).apply { addActionListener { clearPendingImage() } })
    }
    private val status = JTextField(" ").apply { isEditable = false; minimumSize = Dimension(0, 0) }

    /** The quiet honesty line docked at the bottom: a local model can be wrong, with no service behind it. */
    private val honestyFooter = JLabel(Texts[Key.LocalHonestyNote]).apply {
        foreground = BaudetPalette.dim
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        border = EmptyBorder(2, 10, 4, 10)
    }
    /** An amber note shown only when this conversation cannot change files: see [updateToolWarning]. */
    private val toolWarning = JLabel().apply {
        foreground = BaudetPalette.warn
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        border = EmptyBorder(2, 8, 2, 8)
        alignmentX = LEFT_ALIGNMENT
        isVisible = false
    }

    /** Reasoning reads as an aside: gray and italic, so the answer stays dominant. */
    private val reasoningStyle = SimpleAttributeSet().apply {
        StyleConstants.setFontFamily(this, Font.MONOSPACED)
        StyleConstants.setFontSize(this, 13)
        StyleConstants.setForeground(this, BaudetPalette.dim)
        StyleConstants.setItalic(this, true)
    }

    /**
     * The dock above the input. Reasoning used to be written into the transcript, where a
     * thinking model could push the conversation off screen; here it keeps its own rows and
     * its own aside style, and it is still never stored.
     */
    private val thinkingDoc = DefaultStyledDocument()
    private val thinking = JTextPane(thinkingDoc).apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        margin = Insets(8, 8, 8, 8)
    }
    private val thinkingScroll = JScrollPane(thinking).apply { minimumSize = Dimension(0, 0) }
    private val thinkingToggle = JButton(COLLAPSE_ARROW).apply {
        toolTipText = Texts[Key.TipThinkingToggle]
        addActionListener { collapse(!thinkingCollapsed) }
    }
    private val thinkingHeader = JPanel(BorderLayout()).apply {
        isVisible = false
        add(JLabel(Texts[Key.ReasoningLabel]).apply {
            foreground = BaudetPalette.dim
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            border = EmptyBorder(2, 8, 2, 8)
        }, BorderLayout.WEST)
        add(thinkingToggle, BorderLayout.EAST)
    }
    /**
     * Holds the dock above the input. A grid-bag lets each row keep its own height, so a
     * long reasoning block scrolls inside the dock instead of inflating it and pushing the
     * transcript up, which a layout that splits rows equally could not avoid.
     */
    /** Hosts the input alone, or the dock-plus-input split once reasoning is on show. */
    private val stack = JPanel(BorderLayout())

    /** The input's viewport, parented wherever the south block currently needs it. */
    private val inputScroll = JScrollPane(input).apply { minimumSize = Dimension(0, 0) }
    /** The reasoning dock over the input, dragged as one; parented only while the dock shows. */
    private val bottomSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT).apply {
        isContinuousLayout = false   // settles on release, so the size saves then, not per pixel
        resizeWeight = 0.0           // the input keeps its height; the dock absorbs the drag
        isOneTouchExpandable = false
        border = null
    }
    /** Whether the split (dock shown) or the plain input is currently the south CENTER. */
    private var splitMode = false
    /** The dock's height before the user drags: the input's own preferred height, set at build. */
    private var defaultDockHeight = 40
    /** The composer: toolbar, reasoning dock, input and status, as the bottom of [centerSplit]. */
    private val south = JPanel(BorderLayout())
    /**
     * Splits the transcript (top) from the composer (bottom). Its divider sits just above the
     * buttons, so the composer can be made as tall as the transcript, the resize the layout
     * never had before, since only the reasoning and input split was ever draggable.
     */
    private val centerSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT).apply {
        isContinuousLayout = false   // settles on release, so the height saves then, not per pixel
        resizeWeight = 1.0           // window growth enlarges the transcript; the composer keeps its height
        isOneTouchExpandable = false
        border = null
    }
    /** The composer height in pixels; 0 until the first placement, then the value the user wants. */
    private var composerHeight = 0
    /** True while [placeCenter] moves the divider itself, so that move is never treated as a drag. */
    private var placingCenter = false

    private var thinkingCollapsed = false
    private var hasThinking = false

    private var busy = false

    /**
     * The serial worker that opens a conversation off the UI thread. One conversation reads at
     * a time, and only while no turn runs: [loading] holds [send] quiet while a load is in
     * flight, and [busy] holds a load off while a turn runs. That pairing is what keeps store
     * use serial -- no query ever interleaves a turn persist on the shared H2 connection --
     * which is the invariant the codebase already relied on when it kept turn writes and UI
     * reads one at a time. The thread is a daemon so an unfinished read never delays shutdown.
     */
    private val loadExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "baudet-load").apply { isDaemon = true }
    }
    /** Bumped on every conversation switch; a finished load whose generation is stale is dropped. */
    private var loadGeneration = 0
    /** True while a conversation is being read on the worker; [send] stays quiet until it clears. */
    private var loading = false

    /** The worker running the turn in flight, so [stop] can interrupt it; null between turns. */
    private var worker: Thread? = null

    /** Set by [stop] so the catch path reports a stopped turn rather than a failure. */
    @Volatile private var cancelled = false

    /**
     * An instruction typed while a turn streams, held until the cut turn has stopped, then sent as
     * the next message. Touched only on the EDT, so it needs no volatile; null means nothing queued.
     */
    private var steerPending: String? = null

    /** Fired when the list of conversations may have changed, so the sidebar can re-render. */
    var onSessionsChanged: () -> Unit = {}

    init {
        // Send, New session and Compact share a wrapping group that takes the leftover width;
        // Stop is pinned to the far right so the one action that abandons a turn never hides
        // among the others. Center, not West, matters here: a West slot only offers the group
        // its own preferred width, which makes the wrapper drop the last button onto a second
        // row that the single-row toolbar band then clips out of sight. The scroll/status fields
        // still drop their default (== preferred) minimum, so a narrow chat stays resizable.
        val leftGroup = JPanel(WrapLayout(FlowLayout.LEFT, 4, 0))
        leftGroup.add(sendButton)
        leftGroup.add(newSessionButton)
        leftGroup.add(JButton(Texts[Key.CompactButton]).apply { addActionListener { compact() } })
        val buttons = JPanel(BorderLayout())
        buttons.add(leftGroup, BorderLayout.CENTER)
        buttons.add(stopButton, BorderLayout.EAST)

        // A JTextArea sized by rows reports its preferred height before the frame is shown, so
        // the dock's default height is available right here at build time.
        defaultDockHeight = inputScroll.preferredSize.height.coerceAtLeast(40)
        thinkingScroll.preferredSize = Dimension(0, defaultDockHeight)
        // The header sits under the toolbar and the dock straight above the input, so closing
        // the dock hands its rows back to the input without moving anything else around.
        val above = JPanel(BorderLayout())
        above.add(buttons, BorderLayout.NORTH)
        above.add(attachmentStrip, BorderLayout.CENTER)
        above.add(thinkingHeader, BorderLayout.SOUTH)
        south.add(above, BorderLayout.NORTH)
        south.add(stack, BorderLayout.CENTER)
        val bottom = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        status.alignmentX = LEFT_ALIGNMENT
        bottom.add(toolWarning)
        bottom.add(status)
        south.add(bottom, BorderLayout.SOUTH)

        centerSplit.topComponent = transcript.scroll.apply { minimumSize = Dimension(0, 0) }
        applyWrap()
        centerSplit.bottomComponent = south
        add(centerSplit, BorderLayout.CENTER)
        add(honestyFooter, BorderLayout.SOUTH)
        applyHonesty()

        // A released divider is the moment worth saving: the split is not continuous, so this
        // fires on mouse-up rather than per pixel, and only while the dock is actually shown.
        bottomSplit.addPropertyChangeListener { e ->
            if (e.propertyName == "dividerLocation" && splitMode)
                state.rememberReasoningDivider(bottomSplit.dividerLocation)
        }

        // Dragging the composer divider is worth saving on release, not per pixel, and the
        // composer height is the window-independent value, so that is what persists. A placement
        // placeCenter makes itself is ignored: it only echoes back a clamp from a very small window.
        centerSplit.addPropertyChangeListener { e ->
            if (e.propertyName != "dividerLocation" || placingCenter) return@addPropertyChangeListener
            val px = WindowLayout.componentHeightForDivider(
                centerSplit.height, centerSplit.dividerLocation, centerSplit.dividerSize
            )
            if (px > 0 && px != composerHeight) {
                composerHeight = px
                state.rememberComposerHeight(px)
            }
        }
        // The composer keeps its height across window resizes: each size change re-places the
        // divider from the stored height, so the transcript, never the composer, absorbs growth.
        centerSplit.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = placeCenter()
        })

        // Start with no reasoning: the plain input, no divider bar. applyDock brings the split
        // in the first time a thinking turn fills the dock.
        stack.add(inputScroll, BorderLayout.CENTER)

        sendButton.addActionListener { send() }
        val enter = KeyStroke.getKeyStroke("control ENTER")
        val cmdEnter = KeyStroke.getKeyStroke("meta ENTER")
        input.getInputMap(WHEN_FOCUSED).put(enter, SEND_COMMAND)
        input.getInputMap(WHEN_FOCUSED).put(cmdEnter, SEND_COMMAND)
        input.actionMap.put(
            SEND_COMMAND,
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = send()
            }
        )
        // Paste rides a clipboard image onto the message when vision is on; otherwise it is a plain
        // text paste, so the shortcut always does what the user expects on ctrl or cmd plus V.
        val paste = KeyStroke.getKeyStroke("control V")
        val cmdPaste = KeyStroke.getKeyStroke("meta V")
        input.getInputMap(WHEN_FOCUSED).put(paste, PASTE_COMMAND)
        input.getInputMap(WHEN_FOCUSED).put(cmdPaste, PASTE_COMMAND)
        input.actionMap.put(
            PASTE_COMMAND,
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = pasteOrAttach()
            }
        )
    }

    /**
     * Drop the previous aside and let a closed dock open again: called when a turn starts and
     * when the transcript is rebuilt, which are the two moments the old reasoning stops being
     * worth reading.
     */
    private fun resetThinking() {
        thinkingCollapsed = false
        hasThinking = false
        thinkingToggle.text = COLLAPSE_ARROW
        thinkingDoc.remove(0, thinkingDoc.length)
        applyDock()
    }

    private fun collapse(collapsed: Boolean) {
        thinkingCollapsed = collapsed
        thinkingToggle.text = if (collapsed) EXPAND_ARROW else COLLAPSE_ARROW
        applyDock()
    }

    /**
     * Swap the south block between the plain input and the dock-plus-input split. Swapping beats
     * hiding a divider: an invisible top component still leaves a bare bar over an input that has
     * nothing to resize against, the one layout glitch this must never show. The split restores
     * the remembered divider through the same rule the window splits use.
     */
    private fun applyDock() {
        thinkingHeader.isVisible = hasThinking
        val wanted = ThinkingLayout.dockWanted(hasThinking, thinkingCollapsed)
        if (wanted == splitMode) return
        stack.removeAll()
        if (wanted) {
            bottomSplit.topComponent = thinkingScroll
            bottomSplit.bottomComponent = inputScroll
            bottomSplit.dividerLocation = WindowLayout.resolveDivider(
                null, state.config.reasoningDivider, defaultDockHeight
            )
            stack.add(bottomSplit, BorderLayout.CENTER)
        } else {
            stack.add(inputScroll, BorderLayout.CENTER)
        }
        splitMode = wanted
        stack.revalidate()
        stack.repaint()
    }

    /**
     * Place the transcript and composer divider so the composer keeps its height. The stored
     * height is used once the user has dragged it; until then the composer opens at its own
     * preferred height, so nothing jumps on first show. Called on every resize, which is what
     * lets the transcript, not the composer, take the growth.
     */
    private fun placeCenter() {
        val h = centerSplit.height
        if (h <= 0) return
        if (composerHeight <= 0)
            composerHeight = WindowLayout.resolveDialogSize(
                state.config.composerHeight, south.preferredSize.height
            )
        val target = WindowLayout.dividerForHeight(h, composerHeight, centerSplit.dividerSize)
        if (centerSplit.dividerLocation != target) {
            placingCenter = true
            try {
                centerSplit.dividerLocation = target
            } finally {
                placingCenter = false
            }
        }
    }

    /**
     * Fill the dock, following the tail only when the reader was already resting at it. The
     * bounds come from the scroll bar model, which owns all three of them: a bar queried field
     * by field mixes the visible amount with the model and drifts after a resize.
     */
    private fun appendThinking(chunk: String) {
        val bar = thinkingScroll.verticalScrollBar
        val model = bar.model
        val atTail = ThinkingLayout.followsTail(model.value, model.extent, model.maximum, TAIL_SLACK)
        thinkingDoc.insertString(thinkingDoc.length, chunk, reasoningStyle)
        if (atTail) bar.value = bar.maximum
    }

    /**
     * Full redraw from the session state. Reasoning is gone by design: not stored. The step
     * cards are gone with the turn that produced them too, for the reason given on
     * [TranscriptView.rebuild] -- only prompts and answers are in the store.
     */
    /** Re-apply the transcript wrap mode from saved config, so a Settings flip lands without a rebuild. */
    fun applyWrap() {
        transcript.wrap = state.config.transcriptWrap
        transcript.applyWrap()
    }

    /** Show or hide the honesty line from saved config, so a Settings flip lands without a rebuild. */
    fun applyHonesty() { honestyFooter.isVisible = state.config.honestyFooter }

    fun refresh() {
        resetThinking()
        val s = state.session
        // Prefer the persisted rows: a reload then rebuilds the cards, not just the prose.
        if (s.items.isNotEmpty()) transcript.rebuildItems(s.summary, s.items)
        else transcript.rebuild(s.summary, s.messages)
        status.foreground = BaudetPalette.dim
        status.text = statusLine()
        updateToolWarning()
        clearPendingImage()
        applyWrap()
        applyHonesty()
    }

    /**
     * Reflects whether this conversation can change files at all. When the active project
     * has no linked folder, or a folder without the Write right, a turn runs as a plain
     * chat and a request to edit would silently do nothing: this names that up front, in
     * amber, so the fix (link a folder, grant Write) is visible before the user asks. A
     * chat with no project, or tools switched off on purpose, stays quiet by design.
     */
    private fun updateToolWarning() {
        val key = when (state.toolGate()) {
            ToolGate.NoFolder -> Key.WarnNoFolder
            ToolGate.NoWrite -> Key.WarnNoWrite
            else -> null
        }
        if (key == null) {
            toolWarning.isVisible = false
        } else {
            toolWarning.text = Texts[key]
            toolWarning.isVisible = true
        }
    }

    /**
     * Switch the transcript to an existing session chosen in the sidebar. The store read that
     * builds the conversation runs on [loadExecutor], so a long transcript cannot freeze the UI;
     * the swap into [state] stays on the EDT and happens only if this is still the chosen one.
     * During the read the previous transcript stays visible -- no blank frame -- and [send] is
     * held so the read cannot interleave with a turn's writes. Ignored mid-stream.
     */
    fun loadSession(id: String) {
        if (busy) return
        val gen = ++loadGeneration
        loading = true
        status.foreground = BaudetPalette.dim
        status.text = Texts[Key.LoadingSession]
        loadExecutor.execute {
            try {
                val built = state.buildSession(id)
                SwingUtilities.invokeLater {
                    if (gen != loadGeneration) return@invokeLater
                    state.adoptSession(built)
                    loading = false
                    refresh()
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    if (gen != loadGeneration) return@invokeLater
                    loading = false
                    status.foreground = BaudetPalette.error
                    status.text = e.message ?: "?"
                }
            }
        }
    }

    /**
     * Begin a brand-new conversation under the chosen project, mirroring [loadSession]
     * in refusing to tear down a reply in flight. Unlike the New session button this
     * does not persist, so merely clicking through projects leaves no empty chats.
     */
    fun startProjectChat(projectId: String?) {
        if (busy) return
        loadGeneration++
        loading = false
        state.startSessionIn(projectId)
        refresh()
        val name = state.projects().firstOrNull { it.id == projectId }?.name
        status.text = Texts.fmt(Key.ProjectChatStatus, name ?: Texts[Key.InboxNode])
    }

    private fun statusLine(): String {
        val s = state.session
        val wire = if (state.serverSessionActive())
            Texts.fmt(Key.ServerSession, s.previousResponseId ?: "?")
        else Texts[Key.ClientReplay]
        return "${Texts.fmt(Key.MessagesCount, s.messages.size)} · ${state.contextWarning()} · $wire"
    }

    private fun setBusy(b: Boolean) {
        busy = b
        // During a turn the primary button stays live but reads Steer rather than greying out:
        // pressing it folds a new instruction into the running turn instead of losing the keystroke.
        sendButton.isEnabled = true
        sendButton.text = if (b) Texts[Key.SteerButton] else Texts[Key.SendButton]
        stopButton.isEnabled = b
        newSessionButton.isEnabled = !b
        status.foreground = if (b) BaudetPalette.dim else BaudetPalette.ok
    }

    private fun fail(msg: String) {
        status.foreground = BaudetPalette.error
        status.text = msg
    }

    /**
     * Abandon the turn in flight. It closes the live socket through [AppState.cancelTurn], the
     * authoritative stop because a read blocked on the backend ignores a thread interrupt, and
     * interrupts the worker as a nudge for any wait that is interruptible. The partial answer
     * already on screen is kept; only the rest is cut off.
     */
    private fun stop() {
        if (!busy) return
        cancelled = true
        steerPending = null
        state.cancelTurn()
        worker?.interrupt()
    }

    /** Close a turn the user stopped: keep what streamed in, mark the status as stopped. */
    private fun stoppedTurn() {
        status.foreground = BaudetPalette.warn
        status.text = Texts[Key.TurnStopped]
        setBusy(false)
        // A steer queued a follow-up before cutting this turn. The stopped turn has already
        // released the lock -- AppState.send clears it before this callback runs -- so it is safe
        // to start the next message here; the partial answer above stays in the transcript.
        val queued = steerPending
        if (queued != null) {
            steerPending = null
            sendNow(queued)
        }
    }

    /**
     * The pre-send refusal, raised off the EDT after an auto-compaction that still left the
     * conversation over budget. Nothing reached the backend, so no turn ever opened and no event
     * will carry these two rows: the prompt the composer already cleared has to be echoed here,
     * followed by the reason, and then the lock is released.
     */
    private fun overBudget(text: String, image: String?, reason: String) {
        transcript.prompt(text, image != null)
        transcript.failure(reason)
        fail(reason)
        setBusy(false)
    }

    /**
     * Handle a send from the button or the keyboard. Idle, it starts a turn as before; during a
     * running turn it steers rather than being ignored, so an instruction typed mid answer is never
     * dropped and never needs a second press. An empty box does nothing either way.
     */
    private fun send() {
        if (loading) return
        val text = input.text.trim()
        if (busy) {
            if (text.isNotEmpty()) steer(text)
            return
        }
        if (text.isEmpty() && pendingImageDataUrl == null) return
        if (state.config.model.isBlank()) {
            fail(Texts[Key.PickModelFirst])
            return
        }
        sendNow(text, pendingImageDataUrl)
    }

    /**
     * Fold a new instruction into the turn in flight without making the user stop and send by hand.
     * It keeps the answer streamed so far, cuts that turn through the same path as Stop, and queues
     * this text to go out as the next message the moment the stopped turn releases the lock -- see
     * [stoppedTurn]. The follow-up belongs to the same conversation, so the model sees the partial
     * answer it already produced before it is redirected.
     */
    private fun steer(text: String) {
        steerPending = text
        input.text = ""
        status.foreground = BaudetPalette.dim
        status.text = Texts[Key.SteerQueued]
        cancelled = true
        state.cancelTurn()
        worker?.interrupt()
    }

    /** Run one turn: echo the prompt, then ask the backend on a worker thread. */
    private fun sendNow(text: String, image: String? = null) {
        beginTurn(image)

        // A fresh renderer per turn scopes the reasoning dock to one answer; its callbacks fire
        // on the worker thread and completion is handled on the EDT.
        val render = TurnRender()
        val t = Thread {
            try {
                // contextGuard is a cheap local estimate, so it runs here rather than on the
                // EDT. Under the trimming strategies it stays silent and send bounds the window
                // itself; only under StopAtLimit does it refuse, here, before a request goes out.
                state.contextGuard()?.let { reason ->
                    SwingUtilities.invokeLater { overBudget(text, image, reason) }
                    return@Thread
                }
                // The transcript is driven by the turn's events rather than by the text callback:
                // a row has to learn which item a chunk belongs to, and only the events say so.
                // The delta callback is left empty on purpose -- routing it as well would draw
                // every answer twice, once from the callback and once from its item.
                val turn = state.send(text, {}, render::reasoning, image) { transcript.accept(it) }
                SwingUtilities.invokeLater { finishTurn(turn) }
            } catch (e: Exception) {
                // A Stop interrupts this worker; that is a deliberate stop, not a backend failure,
                // so it reports as such instead of scaring the user with an error.
                if (cancelled || e is InterruptedException) SwingUtilities.invokeLater { stoppedTurn() }
                else SwingUtilities.invokeLater { turnFailed(e) }
            } finally {
                // Only this thread may clear the reference. A steer can launch a newer worker before
                // this one unwinds, and clearing it here would orphan the turn that is really running.
                if (worker === Thread.currentThread()) worker = null
            }
        }
        worker = t
        t.start()
    }

    /** Take the busy lock; the turn's first event carries the prompt into the transcript. */
    private fun beginTurn(image: String? = null) {
        input.text = ""
        resetThinking()
        cancelled = false
        setBusy(true)
        status.text = Texts[Key.Streaming]
        // The prompt is the turn's first item, so it is not echoed here -- that is also what keeps
        // it from appearing twice. The picture is the one thing no item carries, so that marker
        // alone is handed to the view in advance, for the row about to arrive.
        if (image != null) transcript.expectImageOnNextPrompt()
        clearPendingImage()
    }

    /**
     * Render a completed turn's outcome on the EDT. Truncation must be visible: a
     * reasoning model can spend the whole budget on its summary and answer with
     * nothing at all, which without this marker reads exactly like a good reply.
     */
    private fun finishTurn(turn: Turn) {
        val counts = "id=${turn.responseId ?: Texts[Key.None]} · " +
            "prompt=${turn.usage?.prompt} · completion=${turn.usage?.completion} · " +
            Texts.fmt(Key.LastEvent, turn.finalEvent ?: "?") + " · ${state.contextWarning()}"
        val incomplete = turn.incompleteReason != null ||
            turn.finalEvent == "response.incomplete"
        if (incomplete) {
            val reason = turn.incompleteReason
            // A loop that stopped at the context ceiling is not a truncation of an answer: it
            // never fired the doomed request. Point at Compact, the same wording a refused
            // overflow gets, so the user knows the way out rather than a bare reason token.
            val label = when {
                reason == "context_limit" -> Texts[Key.ContextOverflow]
                reason != null -> Texts.fmt(Key.Truncated, reason)
                else -> Texts[Key.TruncatedUnknown]
            }
            fail(label + " · " + counts)
        } else {
            status.text = counts
        }
        setBusy(false)
        onSessionsChanged()
    }

    /**
     * Surface a failed turn on the EDT: the error inline, the cause in the status. A prompt the
     * model cannot hold gets its own wording, because the backend's JSON names a token count the
     * user cannot act on: what helps is knowing that Compact is the way out and that it works on
     * an oversized conversation too.
     */
    private fun turnFailed(e: Exception) {
        // The failure text itself already arrived as an error row of the turn, so it is not
        // written again here; what the row cannot know is the way out.
        if (e is BackendException && e.contextOverflow) {
            // The refusal names the window this backend really has, which is knowledge the app did
            // not have before it asked. Recording it is what stops the next attempt repeating the
            // failure: the guard, the tool ceiling and the automatic fold all read that number from
            // then on, so the conversation gets folded instead of sent into a second refusal.
            state.learnContextWindow(e.claimedContextTokens)
            transcript.failure(Texts[Key.ContextOverflow])
            fail(Texts[Key.ContextOverflow])
        } else {
            fail(Texts[Key.TurnFailed])
        }
        setBusy(false)
    }

    /**
     * Routes the reasoning stream to the dock. It stays a callback rather than a row on purpose:
     * the aside is display-only and is never stored, which is what keeps it out of the context
     * sent back to the model. Chunks fire on the worker thread and invokeLater preserves their
     * order, so the first chunk is still the first thing the dock ever shows.
     */
    private inner class TurnRender {

        fun reasoning(chunk: String) = SwingUtilities.invokeLater {
            hasThinking = true
            val firstChunk = thinkingDoc.length == 0
            appendThinking(chunk)
            // Open on the first chunk of a turn only. A dock closed during this turn stays
            // closed for its remainder, so closing the dock is never fought over.
            if (firstChunk) collapse(false) else applyDock()
        }
    }

    /**
     * Handle ctrl or cmd plus V. When the clipboard holds an image it is staged for the next message;
     * otherwise this falls back to a plain text paste so the shortcut always behaves as the user expects.
     * The staged image rides the normal send path, so there is no separate button. Whether the backend
     * accepts an image is the model's decision, not a setting in this app.
     */
    private fun pasteOrAttach() {
        if (busy) {
            input.paste()
            return
        }
        val source = ImageClipboard.readImage()
        if (source != null && source.width > 0 && source.height > 0) {
            stageClipboardImage(source)
            return
        }
        input.paste()
    }

    /** Stage a clipboard image for the next message; the data URL is shown but never stored. */
    private fun stageClipboardImage(source: BufferedImage) {
        pendingImageDataUrl = ImageClipboard.pngDataUrl(source)
        val w = (48.0 * source.width / source.height).toInt().coerceAtLeast(1)
        attachmentLabel.icon = ImageIcon(source.getScaledInstance(w, 48, Image.SCALE_SMOOTH))
        attachmentStrip.isVisible = true
        attachmentStrip.revalidate()
        attachmentStrip.repaint()
        status.foreground = BaudetPalette.ok
        status.text = Texts[Key.ImagePasted]
    }

    /** Drop the staged image; safe to call when there is none. */
    private fun clearPendingImage() {
        pendingImageDataUrl = null
        attachmentLabel.icon = null
        attachmentStrip.isVisible = false
    }

    private fun newSession() {
        if (busy) return
        loadGeneration++
        loading = false
        state.newSession()
        refresh()
        status.text = Texts.fmt(Key.NewSessionStatus, state.session.sessionId)
        onSessionsChanged()
    }

    private fun compact() {
        if (busy) return
        if (state.session.messages.size <= 4) {
            status.text = Texts[Key.TooFewTurns]
            return
        }
        setBusy(true)
        status.text = Texts[Key.Summarising]
        Thread {
            try {
                val outcome = state.compact(keepLast = 4) { }
                SwingUtilities.invokeLater {
                    refresh()
                    // Green only for a fold that happened. A fold that cleared nothing is a fact the
                    // user has to act on -- a turn too big to summarize, or an answer the backend
                    // could not vouch for -- so it reads in the warning colour rather than in the
                    // green of the success it is not.
                    status.foreground =
                        if (outcome is CompactionOutcome.Compacted) BaudetPalette.ok
                        else BaudetPalette.warn
                    status.text = when (outcome) {
                        is CompactionOutcome.Compacted ->
                            Texts.fmt(Key.CompactionDone, outcome.summary.length)
                        CompactionOutcome.NothingToFold -> Texts[Key.TooFewTurns]
                        is CompactionOutcome.OversizedTurn ->
                            Texts.fmt(Key.CompactionOversizedTurn, outcome.maxChars)
                        is CompactionOutcome.SummaryRefused ->
                            Texts.fmt(Key.CompactionRefused, outcome.reason)
                    }
                    setBusy(false)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    // A fold refused for length is the one failure here worth learning from: it names
                    // the window this backend really has, and the fold is usually the first call big
                    // enough to hit it. Recording it bounds the next attempt, so pressing Compact
                    // again works instead of repeating the failure that stranded the conversation.
                    if (e is BackendException && e.contextOverflow)
                        state.learnContextWindow(e.claimedContextTokens)
                    fail(Texts.fmt(Key.CompactionFailed, e.message ?: "?"))
                    setBusy(false)
                }
            }
        }.start()
    }
}
