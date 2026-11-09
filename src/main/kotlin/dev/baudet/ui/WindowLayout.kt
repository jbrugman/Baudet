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
     * Whether a just-closed dialog is worth remembering. Only a size at or above [min] on both
     * axes qualifies, so a dialog dragged to a sliver never becomes the reopened default.
     */
    fun shouldRememberDialogSize(width: Int, height: Int, min: Int): Boolean =
        width >= min && height >= min
}
