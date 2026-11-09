package dev.baudet.ui

import dev.baudet.api.BackendException
import dev.baudet.api.Turn
import dev.baudet.app.AppState
import dev.baudet.app.ToolGate
import dev.baudet.i18n.Key
import dev.baudet.i18n.Texts
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Font
import java.awt.Insets
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
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

        /** How far above the bottom still counts as resting at the tail, in pixels. */
        private const val TAIL_SLACK = 24
        private const val COLLAPSE_ARROW = "\u25be"
        private const val EXPAND_ARROW = "\u25b8"
    }

    private val doc = DefaultStyledDocument()
    private val pane = JTextPane(doc).apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        margin = Insets(8, 8, 8, 8)
    }
    private val scroll = JScrollPane(pane).apply { minimumSize = Dimension(0, 0) }

    private val input = JTextArea(4, 60).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        lineWrap = true
        wrapStyleWord = true
    }
    private val sendButton = JButton(Texts[Key.SendButton])
    private val newSessionButton = JButton(Texts[Key.NewSessionButton]).apply { addActionListener { newSession() } }
    private val status = JTextField(" ").apply { isEditable = false; minimumSize = Dimension(0, 0) }
    /** An amber note shown only when this conversation cannot change files: see [updateToolWarning]. */
    private val toolWarning = JLabel().apply {
        foreground = Color(224, 178, 74)
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        border = EmptyBorder(2, 8, 2, 8)
        alignmentX = LEFT_ALIGNMENT
        isVisible = false
    }

    private val normal = SimpleAttributeSet().apply {
        StyleConstants.setFontFamily(this, Font.MONOSPACED)
        StyleConstants.setFontSize(this, 13)
    }
    private val dim = SimpleAttributeSet(normal).apply {
        StyleConstants.setForeground(this, Color(150, 150, 150))
    }
    private val error = SimpleAttributeSet(normal).apply {
        StyleConstants.setForeground(this, Color(235, 110, 110))
    }
    /** Reasoning reads as an aside: gray and italic, so the answer stays dominant. */
    private val reasoningStyle = SimpleAttributeSet(dim).apply {
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
            foreground = Color(150, 150, 150)
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
    private val stack = JPanel(GridBagLayout())

    /** Full width at the child's own preferred height, so the two rows never share a height. */
    private fun rowConstraints() = GridBagConstraints().apply {
        gridx = 0
        gridwidth = 1
        gridheight = 1
        fill = GridBagConstraints.HORIZONTAL
        weightx = 1.0
        weighty = 0.0
        anchor = GridBagConstraints.NORTHWEST
    }

    /** The renderer of the live turn, so that completion and failure can both flush its tail. */
    private var lastRender: TurnRender? = null
    private var thinkingCollapsed = false
    private var hasThinking = false

    private val bold = SimpleAttributeSet(normal).apply { StyleConstants.setBold(this, true) }
    private val italic = SimpleAttributeSet(normal).apply { StyleConstants.setItalic(this, true) }
    private val heading = SimpleAttributeSet(bold).apply { StyleConstants.setFontSize(this, 15) }
    /**
     * Inline code is the one run that needs a background to read as code, since every glyph in
     * this transcript is already monospaced. A fenced block only tints its lines: a background
     * on a whole line would paint one bar per line, and those bars end wherever the code does.
     */
    private val codeSpan = SimpleAttributeSet(normal).apply {
        StyleConstants.setBackground(this, Color(45, 45, 48))
        StyleConstants.setForeground(this, Color(206, 206, 211))
    }
    private val codeBlock = SimpleAttributeSet(normal).apply {
        StyleConstants.setForeground(this, Color(196, 207, 224))
    }

    /** The parser names an intent; this is where that intent becomes a Swing attribute. */
    private fun attrsFor(kind: SpanKind) = when (kind) {
        SpanKind.Plain -> normal
        SpanKind.Bold -> bold
        SpanKind.Italic -> italic
        SpanKind.Heading -> heading
        SpanKind.Code -> codeSpan
        SpanKind.CodeBlock -> codeBlock
        SpanKind.Marker -> dim
    }

    private var busy = false

    /** Fired when the list of conversations may have changed, so the sidebar can re-render. */
    var onSessionsChanged: () -> Unit = {}

    init {
        val buttons = JPanel(WrapLayout(FlowLayout.LEFT, 4, 0))
        buttons.add(sendButton)
        buttons.add(newSessionButton)
        buttons.add(JButton(Texts[Key.CompactButton]).apply { addActionListener { compact() } })

        // The right pane must stay free to shrink, or the sidebar split caps its
        // width at whatever this column refuses to give up: WrapLayout lets the toolbar
        // wrap, and the scroll/status fields drop their default (== preferred) minimum,
        // so a narrow chat no longer holds the divider hostage.
        val inputScroll = JScrollPane(input).apply { minimumSize = Dimension(0, 0) }
        stack.add(inputScroll, rowConstraints())
        // The dock reads at the input's own height and scrolls beyond it, so reasoning can
        // grow only inside its viewport. A JTextArea sized by rows reports its preferred
        // height before the frame is shown, so this tie is available right here at build time.
        val dockHeight = inputScroll.preferredSize.height.coerceAtLeast(40)
        thinkingScroll.preferredSize = Dimension(0, dockHeight)
        thinkingScroll.maximumSize = Dimension(Int.MAX_VALUE, dockHeight)
        val south = JPanel(BorderLayout())
        // The header sits under the toolbar and the dock straight above the input, so closing
        // the dock hands its rows back to the input without moving anything else around.
        val above = JPanel(BorderLayout())
        above.add(buttons, BorderLayout.NORTH)
        above.add(thinkingHeader, BorderLayout.SOUTH)
        south.add(above, BorderLayout.NORTH)
        south.add(stack, BorderLayout.CENTER)
        val bottom = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        status.alignmentX = LEFT_ALIGNMENT
        bottom.add(toolWarning)
        bottom.add(status)
        south.add(bottom, BorderLayout.SOUTH)

        add(scroll, BorderLayout.CENTER)
        add(south, BorderLayout.SOUTH)

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
    }

    private fun append(text: String, attrs: SimpleAttributeSet = normal) {
        doc.insertString(doc.length, text, attrs)
        pane.caretPosition = doc.length
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
     * Adding and removing beats hiding: an invisible child still reserves its slot, so a hidden
     * dock would leave a gap exactly where the reasoning used to read.
     */
    private fun applyDock() {
        thinkingHeader.isVisible = hasThinking
        val wanted = ThinkingLayout.dockWanted(hasThinking, thinkingCollapsed)
        if (wanted && thinkingScroll.parent == null) stack.add(thinkingScroll, rowConstraints(), 0)
        if (!wanted && thinkingScroll.parent != null) stack.remove(thinkingScroll)
        stack.revalidate()
        stack.repaint()
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

    /** Full redraw from the session state. Reasoning is gone by design: not stored. */
    fun refresh() {
        doc.remove(0, doc.length)
        resetThinking()
        val s = state.session
        s.summary?.let { append(Texts.fmt(Key.SummaryBanner, it.length) + "\n\n", dim) }
        s.messages.forEach { m ->
            // History mirrors the live turn: an answer is re-rendered through the same
            // markdown stream, so a reload shows what the user first saw. A user line
            // stays raw because it is the prompt, not a rendered answer.
            append(if (m.role == "assistant") Texts[Key.BaudetLabel] else Texts[Key.YouLabel], dim)
            if (m.role == "assistant") renderAnswer(m.content) else append(m.content + "\n\n")
        }
        status.foreground = Color(160, 160, 160)
        status.text = statusLine()
        updateToolWarning()
    }

    /**
     * Re-render a stored answer through the same markdown stream a live turn uses, so a
     * reload or session switch shows the styling the user first saw while it streamed in.
     * A user line is not routed here: it is the prompt, kept raw by the caller.
     */
    private fun renderAnswer(content: String) {
        val stream = MarkdownStream { text, kind -> append(text, attrsFor(kind)) }
        stream.write(content)
        stream.flush()
        append("\n\n")
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
     * Switch the transcript to an existing session chosen in the sidebar. Ignored
     * mid-stream, so a reply in flight is never torn down under the user.
     */
    fun loadSession(id: String) {
        if (busy) return
        state.loadSession(id)
        refresh()
    }

    /**
     * Begin a brand-new conversation under the chosen project, mirroring [loadSession]
     * in refusing to tear down a reply in flight. Unlike the New session button this
     * does not persist, so merely clicking through projects leaves no empty chats.
     */
    fun startProjectChat(projectId: String?) {
        if (busy) return
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
        sendButton.isEnabled = !b
        newSessionButton.isEnabled = !b
        status.foreground = if (b) Color(160, 160, 160) else Color(120, 200, 130)
    }

    private fun fail(msg: String) {
        status.foreground = Color(235, 110, 110)
        status.text = msg
    }

    /**
     * The pre-send refusal, now raised off the EDT after an auto-compaction that still left the
     * conversation over budget. The prompt is already echoed (the turn lock is held), so show the
     * reason inline, surface it in the status, and release the lock. Nothing reached the backend.
     */
    private fun overBudget(reason: String) {
        append("\n" + reason + "\n\n", error)
        fail(reason)
        setBusy(false)
    }

    private fun send() {
        if (busy) return
        val text = input.text.trim()
        if (text.isEmpty()) return
        if (state.config.model.isBlank()) {
            fail(Texts[Key.PickModelFirst])
            return
        }
        beginTurn(text)

        // A fresh renderer per turn scopes both the markdown stream and the dock to one
        // answer; callbacks fire on the worker thread, completion on the EDT.
        val render = TurnRender()
        lastRender = render
        Thread {
            try {
                // contextGuard is a cheap local estimate, so it runs here rather than on the
                // EDT. Under the trimming strategies it stays silent and send bounds the window
                // itself; only under StopAtLimit does it refuse, here, before a request goes out.
                state.contextGuard()?.let { reason ->
                    SwingUtilities.invokeLater { overBudget(reason) }
                    return@Thread
                }
                val turn = state.send(text, render::delta, render::reasoning)
                SwingUtilities.invokeLater { finishTurn(turn) }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { turnFailed(e) }
            }
        }.start()
    }

    /** Take the busy lock and echo the prompt into the transcript before the reply. */
    private fun beginTurn(text: String) {
        input.text = ""
        resetThinking()
        setBusy(true)
        status.text = Texts[Key.Streaming]
        append(Texts[Key.YouLabel], dim)
        append(text + "\n\n")
        append(Texts[Key.BaudetLabel], dim)
    }

    /**
     * Render a completed turn's outcome on the EDT. Truncation must be visible: a
     * reasoning model can spend the whole budget on its summary and answer with
     * nothing at all, which without this marker reads exactly like a good reply.
     */
    private fun finishTurn(turn: Turn) {
        lastRender?.flush()
        append("\n\n")
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
        lastRender?.flush()
        append("\n" + Texts.fmt(Key.ErrorPrefix, e.message ?: "?") + "\n\n", error)
        if (e is BackendException && e.contextOverflow) {
            // The refusal names the window this backend really has, which is knowledge the app did
            // not have before it asked. Recording it is what stops the next attempt repeating the
            // failure: the guard, the tool ceiling and the automatic fold all read that number from
            // then on, so the conversation gets folded instead of sent into a second refusal.
            state.learnContextWindow(e.claimedContextTokens)
            append(Texts[Key.ContextOverflow] + "\n\n", error)
            fail(Texts[Key.ContextOverflow])
        } else {
            fail(Texts[Key.TurnFailed])
        }
        setBusy(false)
    }

    /**
     * Routes the two response streams. Reasoning streams ahead of the answer on this backend;
     * both callbacks fire on the one worker thread and invokeLater preserves that order, so the
     * first chunk of the aside is still the first thing the dock ever shows.
     */
    private inner class TurnRender {

        /** Held per turn so that markdown never carries a half-open fence into the next answer. */
        private val markdown = MarkdownStream { text, kind -> append(text, attrsFor(kind)) }

        fun delta(delta: String) = SwingUtilities.invokeLater { markdown.write(delta) }

        /**
         * Release the line the stream is still holding. Both turn endings call this on the EDT,
         * the same thread the writer runs on, so the buffer needs no lock and an answer that
         * stops without a newline still reaches the screen whole.
         */
        fun flush() = markdown.flush()

        fun reasoning(chunk: String) = SwingUtilities.invokeLater {
            hasThinking = true
            val firstChunk = thinkingDoc.length == 0
            appendThinking(chunk)
            // Open on the first chunk of a turn only. A dock closed during this turn stays
            // closed for its remainder, so closing the dock is never fought over.
            if (firstChunk) collapse(false) else applyDock()
        }
    }

    private fun newSession() {
        if (busy) return
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
                val summary = state.compact(keepLast = 4) { }
                SwingUtilities.invokeLater {
                    refresh()
                    status.foreground = Color(120, 200, 130)
                    status.text = if (summary == null) Texts[Key.CompactionEmpty]
                    else Texts.fmt(Key.CompactionDone, summary.length)
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
