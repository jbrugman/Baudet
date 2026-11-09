package dev.baudet.ui

import dev.baudet.i18n.Key
import dev.baudet.i18n.Texts
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Desktop
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.net.URI
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import javax.swing.event.HyperlinkEvent

/**
 * The About window: a modal dialog showing the localized, human-authored description that
 * explains what Baudet is and why it carries that name. The body comes from the i18n table, so
 * switching the UI language switches this text too, and it is rendered through [MarkdownHtml]
 * so the embedded oMLX reference is a real, clickable link. Clicking a link opens it in the
 * user's browser when the platform can browse; where it cannot, the click is simply ignored
 * rather than throwing.
 *
 * Unlike the patch viewer, an About box keeps a fixed, comfortable size and is centered on its
 * owner: it holds a couple of paragraphs, not a document you work in, so remembering a custom
 * shape across launches would be overkill.
 */
object AboutDialog {

    fun open(owner: Component) {
        val parent = SwingUtilities.getWindowAncestor(owner)
        val dialog = JDialog(parent, Texts[Key.AboutTitle], Dialog.ModalityType.APPLICATION_MODAL)
        dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE

        val body = JEditorPane().apply {
            contentType = "text/html"
            isEditable = false
            background = Color(0x2b, 0x2b, 0x2b)
            caret = null
            margin = java.awt.Insets(8, 8, 8, 8)
            // Set the text before the caret position: assigning the position while the document
            // is empty would be reset by the load anyway, so scroll-to-top goes last.
            text = MarkdownHtml.toHtml(Texts[Key.AboutBody])
            addHyperlinkListener { e ->
                if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) browse(e.description)
            }
            caretPosition = 0
        }
        val scroll = JScrollPane(body).apply {
            preferredSize = Dimension(560, 520)
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
        }

        val close = JButton(Texts[Key.CloseButton]).apply {
            addActionListener { dialog.dispose() }
        }
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 6)).apply {
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            add(close)
        }

        dialog.contentPane = JPanel(BorderLayout()).apply {
            add(scroll, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
        dialog.pack()
        dialog.isLocationByPlatform = false
        dialog.setLocationRelativeTo(parent)
        dialog.isVisible = true
    }

    /** Open a URL in the default browser when the desktop supports it; never throw on failure. */
    private fun browse(url: String?) {
        if (url.isNullOrBlank()) return
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            }
        } catch (_: Exception) {
            // A link that cannot open is a cosmetic miss, not a reason to disturb the reader.
        }
    }
}
