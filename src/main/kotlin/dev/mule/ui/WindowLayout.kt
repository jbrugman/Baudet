package dev.mule.ui

/**
 * Chooses a split-pane divider position on a (re)build. The live width carried over from a
 * rebuild wins verbatim -- including zero, so a collapsed side stays collapsed across a
 * language change -- otherwise the stored width is used when it is positive, otherwise the
 * default fallback. Pure of Swing so the rule is testable on its own.
 */
object WindowLayout {

    fun resolveDivider(previous: Int?, persisted: Int, fallback: Int): Int =
        previous ?: persisted.takeIf { it > 0 } ?: fallback
}
