package dev.mule.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.UIManager

/**
 * A "toggle sidebar" glyph: a rounded frame with a divider near one edge, the common
 * convention for showing or hiding a side panel; [rightSide] puts the rail on the right
 * for a panel that lives on the right. It is drawn in the theme's foreground color, so it
 * reads correctly in both FlatLaf themes with no bundled SVG and no resource path -- which
 * matters for the later native-image build.
 */
class SidebarToggleIcon(
    private val width: Int = 20,
    private val height: Int = 18,
    private val rightSide: Boolean = false
) : Icon {

    override fun getIconWidth(): Int = width

    override fun getIconHeight(): Int = height

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = c?.foreground ?: UIManager.getColor("Label.foreground") ?: Color.GRAY
            g2.stroke = BasicStroke(1.5f)
            val w = width - 2
            val h = height - 2
            g2.drawRoundRect(x + 1, y + 1, w, h, 5, 5)
            val frac = if (rightSide) 0.66 else 0.34 // the rail marks the panel it toggles
            val rail = x + 1 + (w * frac).toInt()
            g2.drawLine(rail, y + 1, rail, y + 1 + h)
        } finally {
            g2.dispose()
        }
    }
}
