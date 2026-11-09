package dev.mule.ui

import dev.mule.diff.DiffHighlight
import dev.mule.diff.DiffLineKind
import java.awt.Color
import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.Rectangle
import javax.swing.JDialog
import javax.swing.JScrollPane
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import javax.swing.text.Style
import javax.swing.text.StyleConstants
import javax.swing.text.StyleContext

/**
 * A read-only modal that shows the git patch for one file, or a short reason why there is none.
 * Opened from the file tree on double-click. Must be called on the event dispatch thread, which
 * is where the tree mouse handler already runs. It reopens where the user left it last, at the
 * size they left it at ([rememberedX], [rememberedY], [rememberedW] and [rememberedH], each
 * falling back to a default or to centering when unset), and reports the full bounds it closed
 * at through [onClosed], so a moved or widened viewer is not reset on the next patch. A
 * remembered spot that no longer lands on any screen falls back to centering, exactly as the
 * main window does. Added lines read green and removed lines red, so a patch is legible at a
 * glance; the palette is tuned for the dark FlatLaf theme rather than a light editor.
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
        val pane = JTextPane().apply {
            isEditable = false
            margin = Insets(8, 8, 8, 8)
        }
        renderDiff(pane, text)
        dialog.contentPane.add(JScrollPane(pane).apply {
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
     * Fill [pane] with [text] line by line, coloring each line by its diff kind. The text is
     * inserted straight into the styled document instead of through setText, because the viewer
     * shows raw patch text that can contain braces and backslashes, which a rich-text reader
     * would otherwise try to interpret. A trailing empty line is preserved, since splitting on
     * a newline keeps it and rejoining with the same separator is lossless.
     */
    private fun renderDiff(pane: JTextPane, text: String) {
        val context = StyleContext()
        val base = context.addStyle("base", null).apply {
            StyleConstants.setFontFamily(this, Font.MONOSPACED)
            StyleConstants.setFontSize(this, 12)
        }
        val styles = DiffLineKind.entries.associateWith { kind -> styleFor(context, base, kind) }
        val doc = pane.styledDocument
        val lines = text.split("\n")
        lines.forEachIndexed { index, line ->
            doc.insertString(doc.length, line, styles.getValue(DiffHighlight.classify(line)))
            if (index < lines.lastIndex) doc.insertString(doc.length, "\n", base)
        }
        pane.caretPosition = 0
    }

    /** The style for one kind, inheriting the monospaced base; context keeps the default color. */
    private fun styleFor(context: StyleContext, base: Style, kind: DiffLineKind): Style {
        val style = context.addStyle(kind.name, base)
        when (kind) {
            DiffLineKind.Added -> StyleConstants.setForeground(style, COLOR_ADDED)
            DiffLineKind.Removed -> StyleConstants.setForeground(style, COLOR_REMOVED)
            DiffLineKind.FileHeader -> {
                StyleConstants.setForeground(style, COLOR_FILE_HEADER)
                StyleConstants.setBold(style, true)
            }
            DiffLineKind.HunkHeader -> StyleConstants.setForeground(style, COLOR_HUNK_HEADER)
            DiffLineKind.Meta -> StyleConstants.setForeground(style, COLOR_META)
            DiffLineKind.Context -> Unit
        }
        return style
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

    // GitHub-dark palette, chosen so each kind stays readable on the dark editor background.
    private val COLOR_ADDED = Color(0x7E, 0xE7, 0x87)
    private val COLOR_REMOVED = Color(0xFF, 0x7B, 0x72)
    private val COLOR_FILE_HEADER = Color(0xE6, 0xED, 0xF3)
    private val COLOR_HUNK_HEADER = Color(0x79, 0xC0, 0xFF)
    private val COLOR_META = Color(0x8B, 0x94, 0x9E)

    private const val DEFAULT_WIDTH = 560
    private const val DEFAULT_HEIGHT = 360
    private const val MIN_SIZE = 200
}
