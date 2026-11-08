package dev.mule.ui

import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.Font
import java.awt.Insets
import javax.swing.JDialog
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * A read-only modal that shows the git patch for one file, or a short reason why there is none.
 * Opened from the file tree on double-click. Must be called on the event dispatch thread, which
 * is where the tree mouse handler already runs.
 */
object DiffViewDialog {

    fun open(owner: Component, title: String, text: String) {
        val parent = SwingUtilities.getWindowAncestor(owner)
        val dialog = JDialog(parent, title, Dialog.ModalityType.APPLICATION_MODAL)
        dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        val area = JTextArea(text).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            margin = Insets(8, 8, 8, 8)
            caretPosition = 0
        }
        dialog.contentPane.add(JScrollPane(area).apply { preferredSize = Dimension(560, 360) })
        dialog.pack()
        dialog.setLocationRelativeTo(owner)
        dialog.isVisible = true
    }
}
