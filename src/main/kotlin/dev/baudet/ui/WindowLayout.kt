package dev.baudet.ui

/**
 * Chooses a split-pane divider position on a (re)build, and resolves the remembered size
 * of a dialog, so both layout rules stay pure of Swing and testable on their own.
 */
object WindowLayout {

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
