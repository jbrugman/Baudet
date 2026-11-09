package dev.baudet.tool

/**
 * The result of folding edits into a file's text, kept as a value so every path
 * -- including a rejection -- is testable without touching the disk. A rejection
 * never reports a half-patched file: either every edit landed, or the caller gets
 * the one precise reason it stopped. The reason text is model-facing, so it stays
 * English: it is fed straight back to the model so it can correct itself.
 */
sealed class PatchOutcome {
    /** Every edit applied; [content] is the new full text. */
    data class Applied(val content: String) : PatchOutcome()

    /** Nothing was applied; [reason] names the single edit that did not land. */
    data class Rejected(val reason: String) : PatchOutcome()
}
