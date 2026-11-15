package dev.baudet.ui

import java.awt.GraphicsEnvironment
import java.awt.Rectangle

/**
 * Chooses a split-pane divider position on a (re)build, resolves the remembered size of a dialog,
 * and decides whether a remembered window position is still a spot the user can reach, so all three
 * layout rules stay pure of Swing and testable on their own.
 */
object WindowLayout {

    /**
     * The screens as separate rectangles, which is the only shape that can tell a gap between two
     * monitors from a monitor. The union of them is useless to this rule, because a union has no
     * gaps: a remembered spot in the dead space between two screens reads as inside it. A device
     * reporting no configuration is skipped rather than trusted, and an empty list then makes every
     * remembered spot unreachable, which is the safe answer on a headless or changed display.
     */
    fun screens(): List<Rectangle> =
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .mapNotNull { it.defaultConfiguration?.bounds }

    /**
     * Whether a remembered window origin is a spot the window can still be moved from. The rule is
     * about the title bar rather than the window: the origin is the window's top-left corner, where
     * the bar begins, so it has to land inside one real screen. No union can answer this. It has no
     * gaps, so a spot left behind by a monitor that has gone and now sitting between two screens
     * passes it, and a rectangle grazing a screen edge by one pixel passes it too; either way the
     * bar ends up off-screen, and a window whose bar is off-screen cannot be dragged back, because
     * dragging a window means dragging that bar. Callers fall back to centering.
     */
    fun rememberedOriginIsReachable(x: Int, y: Int, screens: List<Rectangle>): Boolean =
        screens.any { it.contains(x, y) }

    fun resolveDivider(previous: Int?, persisted: Int, fallback: Int): Int =
        previous ?: persisted.takeIf { it > 0 } ?: fallback

    /**
     * The remembered dialog dimension when one was stored, otherwise [fallback]. Zero means
     * never stored, mirroring the split-pane rule, so a fresh install still opens at a sane size.
     */
    fun resolveDialogSize(remembered: Int, fallback: Int): Int =
        remembered.takeIf { it > 0 } ?: fallback

    /**
     * The divider location that leaves [componentHeight] pixels for the bottom component of a
     * vertical split of total [splitHeight], so the composer keeps its height and the transcript
     * takes the rest. Clamped at zero for the case where the composer is taller than the split.
     */
    fun dividerForHeight(splitHeight: Int, componentHeight: Int, dividerSize: Int): Int =
        (splitHeight - componentHeight - dividerSize).coerceAtLeast(0)

    /**
     * The inverse of [dividerForHeight]: the bottom-component height implied by a divider
     * location, so a released drag can be saved as a window-independent height. Clamped at zero
     * because a drag can never leave less than nothing below the divider.
     */
    fun componentHeightForDivider(splitHeight: Int, dividerLocation: Int, dividerSize: Int): Int =
        (splitHeight - dividerLocation - dividerSize).coerceAtLeast(0)

    /**
     * Whether a just-closed dialog is worth remembering. Only a size at or above [min] on both
     * axes qualifies, so a dialog dragged to a sliver never becomes the reopened default.
     */
    fun shouldRememberDialogSize(width: Int, height: Int, min: Int): Boolean =
        width >= min && height >= min
}
