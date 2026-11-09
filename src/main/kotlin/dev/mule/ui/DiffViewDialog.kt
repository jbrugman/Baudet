package dev.mule.ui

import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.Rectangle
import javax.swing.JDialog
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * A read-only modal that shows the git patch for one file, or a short reason why there is none.
 * Opened from the file tree on double-click. Must be called on the event dispatch thread, which
 * is where the tree mouse handler already runs. It reopens where the user left it last, at the
 * size they left it at ([rememberedX], [rememberedY], [rememberedW] and [rememberedH], each
 * falling back to a default or to centering when unset), and reports the full bounds it closed
 * at through [onClosed], so a moved or widened viewer is not reset on the next patch. A
 * remembered spot that no longer lands on any screen falls back to centering, exactly as the
 * main window does.
 */
object DiffViewDialog {

    fun open(
        owner: Component,
        title: String,
        text: String,
        rememberedX: Int,
        rememberedY: Int,
        rememberedW: Int,
        rememberedH: Int,
        onClosed: (x: Int, y: Int, width: Int, height: Int) -> Unit
    ) {
        val parent = SwingUtilities.getWindowAncestor(owner)
        val dialog = JDialog(parent, title, Dialog.ModalityType.APPLICATION_MODAL)
        // Hide rather than dispose on close, so the final bounds can still be read back before
        // we dispose it ourselves: a disposed window may have released the native peer that
        // reports its bounds, and those bounds are exactly what we want to remember.
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
        // pack realizes the native peer, so the placement below lands: a location set before
        // the peer exists is dropped on macOS, the same reason the main window only trusts a
        // saved spot once the window is displayable.
        dialog.pack()
        place(dialog, owner, rememberedX, rememberedY, rememberedW, rememberedH)
        dialog.isVisible = true
        val x = dialog.x
        val y = dialog.y
        val width = dialog.width
        val height = dialog.height
        dialog.dispose()
        if (WindowLayout.shouldRememberDialogSize(width, height, MIN_SIZE))
            onClosed(x, y, width, height)
    }

    /**
     * Put the dialog at the spot the user left it when a size was stored and that spot still
     * lands on a screen; otherwise center it on [owner]. The stored size is the flag that a
     * position exists at all, so a first open centers rather than jumping to the origin.
     */
    private fun place(
        dialog: JDialog,
        owner: Component,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ) {
        if (w <= 0 || h <= 0) {
            dialog.setLocationRelativeTo(owner)
            return
        }
        val saved = Rectangle(x, y, w, h)
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        if (screen.intersects(saved)) dialog.setLocation(x, y)
        else dialog.setLocationRelativeTo(owner)
    }

    private const val DEFAULT_WIDTH = 560
    private const val DEFAULT_HEIGHT = 360
    private const val MIN_SIZE = 200
}
