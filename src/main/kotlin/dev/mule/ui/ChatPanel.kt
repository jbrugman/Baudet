package dev.mule.ui

import dev.mule.app.AppState
import dev.mule.i18n.Key
import dev.mule.i18n.Texts
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
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

    private val doc = DefaultStyledDocument()
    private val pane = JTextPane(doc).apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        margin = java.awt.Insets(8, 8, 8, 8)
    }
    private val scroll = JScrollPane(pane)

    private val input = javax.swing.JTextArea(4, 60).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        lineWrap = true
        wrapStyleWord = true
    }
    private val sendButton = JButton(Texts[Key.sendButton])
    private val status = javax.swing.JTextField(" ").apply { isEditable = false }

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
    /** Reasoning reads as a aside: grey and italic, so the answer stays dominant. */
    private val reasoningStyle = SimpleAttributeSet(dim).apply {
        StyleConstants.setItalic(this, true)
    }

    private var busy = false

    init {
        val buttons = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT))
        buttons.add(sendButton)
        buttons.add(JButton(Texts[Key.newSessionButton]).apply { addActionListener { newSession() } })
        buttons.add(JButton(Texts[Key.compactButton]).apply { addActionListener { compact() } })

        val south = JPanel(BorderLayout())
        south.add(buttons, BorderLayout.NORTH)
        south.add(JScrollPane(input), BorderLayout.CENTER)
        south.add(status, BorderLayout.SOUTH)

        add(scroll, BorderLayout.CENTER)
        add(south, BorderLayout.SOUTH)

        sendButton.addActionListener { send() }
        val enter = KeyStroke.getKeyStroke("control ENTER")
        val cmdEnter = KeyStroke.getKeyStroke("meta ENTER")
        input.getInputMap(JComponent.WHEN_FOCUSED).put(enter, "mule-send")
        input.getInputMap(JComponent.WHEN_FOCUSED).put(cmdEnter, "mule-send")
        input.getActionMap().put(
            "mule-send",
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
        s.summary?.let { append(Texts.fmt(Key.summaryBanner, it.length) + "\n\n", dim) }
        s.messages.forEach { m ->
            append("${m.role}: ", dim)
            append(m.content + "\n\n")
        }
        status.foreground = Color(160, 160, 160)
        status.text = statusLine()
    }

    private fun statusLine(): String {
        val s = state.session
        val wire = if (state.serverSessionActive())
            Texts.fmt(Key.serverSession, s.previousResponseId ?: "?")
        else Texts[Key.clientReplay]
        return "${Texts.fmt(Key.messagesCount, s.messages.size)} · ${state.contextWarning()} · $wire"
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
            fail(Texts[Key.pickModelFirst])
            return
        }
        input.text = ""
        setBusy(true)
        status.text = Texts[Key.streaming]
        append(Texts[Key.youLabel], dim)
        append(text + "\n\n")
        append(Texts[Key.muleLabel], dim)

        Thread {
            // Reasoning streams ahead of the answer on this backend. Both callbacks
            // fire on this one worker thread and invokeLater preserves submission
            // order, so labelling the first chunk of each kind reads correctly.
            var sawReasoning = false
            var labelledAnswer = false
            try {
                val turn = state.send(
                    text,
                    onDelta = { delta ->
                        SwingUtilities.invokeLater {
                            if (sawReasoning && !labelledAnswer) {
                                labelledAnswer = true
                                append("\n" + Texts[Key.answerLabel] + "\n", dim)
                            }
                            append(delta)
                        }
                    },
                    onReasoning = { chunk ->
                        SwingUtilities.invokeLater {
                            if (!sawReasoning) {
                                sawReasoning = true
                                append(Texts[Key.reasoningLabel] + "\n", dim)
                            }
                            append(chunk, reasoningStyle)
                        }
                    }
                )
                SwingUtilities.invokeLater {
                    append("\n\n")
                    val counts = "id=${turn.responseId ?: Texts[Key.none]} · " +
                        "prompt=${turn.usage?.prompt} · completion=${turn.usage?.completion} · " +
                        Texts.fmt(Key.lastEvent, turn.finalEvent ?: "?") + " · ${state.contextWarning()}"
                    // Truncation must be visible: a reasoning model can spend the whole
                    // budget on its summary and answer with nothing at all, which without
                    // this marker reads exactly like a successful reply.
                    val incomplete = turn.incompleteReason != null ||
                        turn.finalEvent == "response.incomplete"
                    if (incomplete) {
                        val reason = turn.incompleteReason
                        fail((if (reason != null) Texts.fmt(Key.truncated, reason)
                        else Texts[Key.truncatedUnknown]) + " · " + counts)
                    } else {
                        status.text = counts
                    }
                    setBusy(false)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    append("\n" + Texts.fmt(Key.errorPrefix, e.message ?: "?") + "\n\n", error)
                    fail(Texts[Key.turnFailed])
                    setBusy(false)
                }
            }
        }.start()
    }

    private fun newSession() {
        state.newSession()
        refresh()
        status.text = Texts.fmt(Key.newSessionStatus, state.session.sessionId)
    }

    private fun compact() {
        if (busy) return
        if (state.session.messages.size <= 4) {
            status.text = Texts[Key.tooFewTurns]
            return
        }
        setBusy(true)
        status.text = Texts[Key.summarising]
        Thread {
            try {
                val summary = state.compact(keepLast = 4) { }
                SwingUtilities.invokeLater {
                    refresh()
                    status.foreground = Color(120, 200, 130)
                    status.text = if (summary == null) Texts[Key.compactionEmpty]
                    else Texts.fmt(Key.compactionDone, summary.length)
                    setBusy(false)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    fail(Texts.fmt(Key.compactionFailed, e.message ?: "?"))
                    setBusy(false)
                }
            }
        }.start()
    }
}
