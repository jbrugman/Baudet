package dev.baudet.ui

import dev.baudet.i18n.Key
import dev.baudet.i18n.Texts
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * The per-tab help window. Where [AboutDialog] explains what Baudet is, this explains how to use
 * it: the caller passes the body that matches the active tab, so a click on Help opens guidance
 * for Chat, Models or Settings rather than one long scroll. The body is a curated i18n string
 * rendered through [MarkdownHtml], so switching the UI language switches the help text too.
 *
 * Like About it keeps a fixed, comfortable size and centers on its owner: help is read, not worked
 * in, so remembering a custom shape across launches would be overkill. The tab-to-body choice is
 * isolated in [bodyKeyFor] so that the only real logic here stays testable without opening a window.
 */
object HelpDialog {

    /** Map a tabbed-pane index to the help body for that tab; anything else falls back to Chat. */
    fun bodyKeyFor(selectedIndex: Int): Key = when (selectedIndex) {
        1 -> Key.HelpBodyModels
        2 -> Key.HelpBodySettings
        else -> Key.HelpBodyChat
    }

    /** Open the help window for [bodyKey], localized to the current UI language. */
    fun open(owner: Component, bodyKey: Key) {
        val parent = SwingUtilities.getWindowAncestor(owner)
        val dialog = JDialog(parent, Texts[Key.HelpGuide], Dialog.ModalityType.APPLICATION_MODAL)
        dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE

        val body = JEditorPane().apply {
            contentType = "text/html"
            isEditable = false
            background = Color(0x2b, 0x2b, 0x2b)
            caret = null
            margin = java.awt.Insets(8, 8, 8, 8)
            text = MarkdownHtml.toHtml(Texts[bodyKey])
        }
        val scroll = JScrollPane(body).apply {
            preferredSize = Dimension(620, 560)
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
}
