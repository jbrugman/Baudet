package dev.mule.ui

import dev.mule.api.Turn
import dev.mule.app.AppState
import dev.mule.i18n.Key
import dev.mule.i18n.Texts
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.text.DefaultStyledDocument
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * Transcript plus input. The stream is written straight into the document
 * layer; the status line shows response_id and the token counts, because those
 * are the two numbers that reveal whether the server-side session and the
 * prefix cache are actually working.
 *
 * Reasoning is rendered but never stored: it is display-only and disappears on
 * the next redraw, which keeps it out of the context sent back to the model.
 */
class ChatPanel(private val state: AppState) : JPanel(BorderLayout()) {

    companion object { private const val SEND_COMMAND = "mule-send" }

    private val doc = DefaultStyledDocument()
    private val pane = JTextPane(doc).apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        margin = java.awt.Insets(8, 8, 8, 8)
    }
    private val scroll = JScrollPane(pane).apply { minimumSize = java.awt.Dimension(0, 0) }

    private val input = javax.swing.JTextArea(4, 60).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        lineWrap = true
        wrapStyleWord = true
    }
    private val sendButton = JButton(Texts[Key.SendButton])
    private val status = javax.swing.JTextField(" ").apply { isEditable = false; minimumSize = java.awt.Dimension(0, 0) }

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

    private var busy = false

    /** Fired when the list of conversations may have changed, so the sidebar can re-render. */
    var onSessionsChanged: () -> Unit = {}

    init {
        val buttons = JPanel(WrapLayout(java.awt.FlowLayout.LEFT, 4, 0))
        buttons.add(sendButton)
        buttons.add(JButton(Texts[Key.NewSessionButton]).apply { addActionListener { newSession() } })
        buttons.add(JButton(Texts[Key.CompactButton]).apply { addActionListener { compact() } })

        // The right pane must stay free to shrink, or the sidebar split caps its
        // width at whatever this column refuses to give up: WrapLayout lets the toolbar
        // wrap, and the scroll/status fields drop their default (== preferred) minimum,
        // so a narrow chat no longer holds the divider hostage.
        val inputScroll = JScrollPane(input).apply { minimumSize = java.awt.Dimension(0, 0) }
        val south = JPanel(BorderLayout())
        south.add(buttons, BorderLayout.NORTH)
        south.add(inputScroll, BorderLayout.CENTER)
        south.add(status, BorderLayout.SOUTH)

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

    /** Full redraw from the session state. Reasoning is gone by design: not stored. */
    fun refresh() {
        doc.remove(0, doc.length)
        val s = state.session
        s.summary?.let { append(Texts.fmt(Key.SummaryBanner, it.length) + "\n\n", dim) }
        s.messages.forEach { m ->
            append("${m.role}: ", dim)
            append(m.content + "\n\n")
        }
        status.foreground = Color(160, 160, 160)
        status.text = statusLine()
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
        status.foreground = if (b) Color(160, 160, 160) else Color(120, 200, 130)
    }

    private fun fail(msg: String) {
        status.foreground = Color(235, 110, 110)
        status.text = msg
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

        // A fresh renderer per turn keeps the reasoning/answer labeling scoped to
        // this one stream; callbacks fire on the worker thread, completion on the EDT.
        val render = TurnRender()
        Thread {
            try {
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
        setBusy(true)
        status.text = Texts[Key.Streaming]
        append(Texts[Key.YouLabel], dim)
        append(text + "\n\n")
        append(Texts[Key.MuleLabel], dim)
    }

    /**
     * Render a completed turn's outcome on the EDT. Truncation must be visible: a
     * reasoning model can spend the whole budget on its summary and answer with
     * nothing at all, which without this marker reads exactly like a good reply.
     */
    private fun finishTurn(turn: Turn) {
        append("\n\n")
        val counts = "id=${turn.responseId ?: Texts[Key.None]} · " +
            "prompt=${turn.usage?.prompt} · completion=${turn.usage?.completion} · " +
            Texts.fmt(Key.LastEvent, turn.finalEvent ?: "?") + " · ${state.contextWarning()}"
        val incomplete = turn.incompleteReason != null ||
            turn.finalEvent == "response.incomplete"
        if (incomplete) {
            val reason = turn.incompleteReason
            fail((if (reason != null) Texts.fmt(Key.Truncated, reason)
            else Texts[Key.TruncatedUnknown]) + " · " + counts)
        } else {
            status.text = counts
        }
        setBusy(false)
        onSessionsChanged()
    }

    /** Surface a failed turn on the EDT: the error inline, the cause in the status. */
    private fun turnFailed(e: Exception) {
        append("\n" + Texts.fmt(Key.ErrorPrefix, e.message ?: "?") + "\n\n", error)
        fail(Texts[Key.TurnFailed])
        setBusy(false)
    }

    /**
     * Turns the two response streams into transcript writes. Reasoning streams
     * ahead of the answer on this backend; both callbacks fire on the one worker
     * thread and invokeLater preserves submission order, so the first chunk of each
     * kind is labeled exactly once.
     */
    private inner class TurnRender {
        private var sawReasoning = false
        private var labelledAnswer = false

        fun delta(delta: String) = SwingUtilities.invokeLater {
            if (sawReasoning && !labelledAnswer) {
                labelledAnswer = true
                append("\n" + Texts[Key.AnswerLabel] + "\n", dim)
            }
            append(delta)
        }

        fun reasoning(chunk: String) = SwingUtilities.invokeLater {
            if (!sawReasoning) {
                sawReasoning = true
                append(Texts[Key.ReasoningLabel] + "\n", dim)
            }
            append(chunk, reasoningStyle)
        }
    }

    private fun newSession() {
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
                    fail(Texts.fmt(Key.CompactionFailed, e.message ?: "?"))
                    setBusy(false)
                }
            }
        }.start()
    }
}
