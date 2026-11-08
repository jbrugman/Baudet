package dev.mule.ui

import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

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
        p.add(JLabel(label), c)
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
