package dev.mule.ui

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout

/**
 * A FlowLayout whose preferred and minimum sizes are width-aware. Plain
 * FlowLayout reports preferred == minimum == one row, so as a split-pane child it
 * pins the divider wide: the content can never shrink even though layoutContainer
 * already wraps. This overrides only the two size methods to simulate that same
 * wrap; layoutContainer is inherited and does the real, already-wrapping layout.
 */
class WrapLayout(
    align: Int = LEFT,
    hgap: Int = 3,
    vgap: Int = 3
) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension {
        synchronized(target.treeLock) {
            val insets = target.insets
            val available = target.width - insets.left - insets.right
            // Not sized yet: report a single row rather than a stacked column.
            val maxWidth = if (available <= 0) Int.MAX_VALUE else available
            return wrapSize(target, maxWidth, minimum = false)
        }
    }

    override fun minimumLayoutSize(target: Container): Dimension {
        synchronized(target.treeLock) {
            val widest = target.components.filter { it.isVisible }
                .maxOfOrNull { it.minimumSize.width } ?: 0
            return wrapSize(target, widest, minimum = true)
        }
    }

    /** Greedy row-fill mirroring FlowLayout, measured at the given inner width. */
    private fun wrapSize(target: Container, maxWidth: Int, minimum: Boolean): Dimension {
        val insets = target.insets
        var rowWidth = 0
        var rowHeight = 0
        var widestRow = 0
        var totalHeight = 0

        for (c in target.components) {
            if (!c.isVisible) continue
            val d = if (minimum) c.minimumSize else c.preferredSize
            if (rowWidth == 0) {
                rowWidth = d.width
                rowHeight = d.height
            } else if (rowWidth + hgap + d.width > maxWidth) {
                widestRow = maxOf(widestRow, rowWidth)
                totalHeight += rowHeight + vgap
                rowWidth = d.width
                rowHeight = d.height
            } else {
                rowWidth += hgap + d.width
                rowHeight = maxOf(rowHeight, d.height)
            }
        }
        if (rowWidth > 0) {
            widestRow = maxOf(widestRow, rowWidth)
            totalHeight += rowHeight
        }
        return Dimension(
            widestRow + insets.left + insets.right,
            totalHeight + insets.top + insets.bottom
        )
    }
}
