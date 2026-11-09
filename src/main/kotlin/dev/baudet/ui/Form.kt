package dev.baudet.ui

import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

/** Plain form: label on the left, field on the right, fields stretch. */
fun formPanel(rows: List<Pair<String, JComponent>>, gap: Int = 6): JPanel {
    val p = JPanel(GridBagLayout())
    val c = GridBagConstraints()
    c.insets = Insets(4, gap, 4, gap)
    c.anchor = GridBagConstraints.WEST
    rows.forEachIndexed { i, (label, field) ->
        c.gridx = 0
        c.gridy = i
        c.weightx = 0.0
        c.fill = GridBagConstraints.NONE
        val caption = JLabel(label)
        // The hint belongs to the row rather than to the control alone: the reader
        // hovers the caption, so mirror whatever tip the field carries onto it. A
        // blank caption stays bare, because its control already owns the target.
        if (label.isNotBlank() && !field.toolTipText.isNullOrBlank()) caption.toolTipText = field.toolTipText
        p.add(caption, c)
        c.gridx = 1
        c.weightx = 1.0
        c.fill = GridBagConstraints.HORIZONTAL
        p.add(field, c)
    }
// filler below the rows: keeps them top-aligned instead of centred
    c.gridx = 0
    c.gridy = rows.size
    c.gridwidth = 2
    c.weighty = 1.0
    c.weightx = 1.0
    c.fill = GridBagConstraints.BOTH
    val filler = JPanel()
    filler.preferredSize = Dimension(1, 1)
    p.add(filler, c)
    return p
}

/**
 * A multi-line field that keeps the height it was declared with. A text area outside a
 * viewport asks for as much vertical space as its content holds, so a long system prompt
 * stretched its own row past the bottom of the window and took the buttons below it out of
 * sight. Inside a viewport the declared rows become the visible window and the remainder
 * scrolls, which is the only way the rest of the form stays reachable.
 */
fun scrollingField(field: JTextArea): JScrollPane {
    val scroll = JScrollPane(field)
    // The field wraps its own lines, so a horizontal bar would only claim a width the text
    // never actually needs.
    scroll.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    // The form mirrors the hint onto the caption from the component it adds, and that is now
    // the wrapper, so the hint has to ride along with it.
    field.toolTipText?.takeIf { it.isNotBlank() }?.let { scroll.toolTipText = it }
    return scroll
}
