package dev.mule.session

/**
 * The arithmetic of compaction, apart from the call that performs it: which
 * turns get folded, and the text handed to the model. Kept pure so folding the
 * wrong turns is a failing test rather than a lost conversation.
 */
object CompactionPlan {

    /** Index of the first turn to keep verbatim, or null when there is nothing to fold. */
    fun splitAt(size: Int, keepLast: Int): Int? =
        if (size <= keepLast) null else size - keepLast

    /** Everything being folded, plus the summary it replaces. */
    fun source(summary: String?, older: List<Msg>): String = buildString {
        // Model-facing: English regardless of the UI language.
        summary?.let { append("(previous summary) ").append(it).append("\n\n") }
        older.forEach { m -> append(m.role).append(": ").append(m.content).append("\n\n") }
    }
}
