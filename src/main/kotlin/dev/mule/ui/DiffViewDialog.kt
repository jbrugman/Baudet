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
 * is where the tree mouse handler already runs. It opens at the size the user left it at last
 * ([rememberedW] and [rememberedH], falling back to a default when unset) and reports the size it
 * was closed at through [onResized], so a widened viewer is not reset on the next patch.
 */
object DiffViewDialog {

    fun open(
        owner: Component,
        title: String,
        text: String,
        rememberedW: Int,
        rememberedH: Int,
        onResized: (width: Int, height: Int) -> Unit
    ) {
        val parent = SwingUtilities.getWindowAncestor(owner)
        val dialog = JDialog(parent, title, Dialog.ModalityType.APPLICATION_MODAL)
        // Hide rather than dispose on close, so the final bounds can still be read back before
        // we dispose it ourselves: a disposed window may have released the native peer that
        // reports its size, and that size is exactly what we want to remember.
        dialog.defaultCloseOperation = WindowConstants.HIDE_ON_CLOSE
        val area = JTextArea(text).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            margin = Insets(8, 8, 8, 8)
            caretPosition = 0
        }
        dialog.contentPane.add(JScrollPane(area).apply {
            preferredSize = Dimension(
                WindowLayout.resolveDialogSize(rememberedW, DEFAULT_WIDTH),
                WindowLayout.resolveDialogSize(rememberedH, DEFAULT_HEIGHT)
            )
        })
        dialog.pack()
        dialog.setLocationRelativeTo(owner)
        dialog.isVisible = true
        val width = dialog.width
        val height = dialog.height
        dialog.dispose()
        if (WindowLayout.shouldRememberDialogSize(width, height, MIN_SIZE))
            onResized(width, height)
    }

    private const val DEFAULT_WIDTH = 560
    private const val DEFAULT_HEIGHT = 360
    private const val MIN_SIZE = 200
}
