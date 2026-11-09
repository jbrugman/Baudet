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

    /**
     * [source] cut to [maxChars], so a batch that cannot fit is still summarized instead of
     * sent whole. The cut falls at the end, which only bites on a single turn larger than the
     * ceiling: a batch is picked to fit, so this is the last resort rather than the norm.
     */
    fun source(summary: String?, older: List<Msg>, maxChars: Int): String {
        val full = source(summary, older)
        if (maxChars <= 0 || full.length <= maxChars) return full
        // Model-facing marker, like the rest of this file.
        return full.substring(0, maxChars) + "\n\n[... text too long for this call, cut here ...]"
    }

    /**
     * The oldest turns that fit into one summarization call next to a summary already
     * [summaryChars] characters long, and the turns left over for the call after it. Batching
     * is what lets a conversation that is already past the backend's window still be folded:
     * the fold must never need the whole conversation to fit into a single call, or compacting
     * fails for exactly the conversation that needs it. Always takes at least one turn, so a
     * single oversized turn still advances the fold instead of stalling it.
     *
     * @return the batch to summarize now, and everything still to fold
     */
    fun foldBatch(older: List<Msg>, summaryChars: Int, maxChars: Int): Pair<List<Msg>, List<Msg>> {
        var room = (maxChars - summaryChars).coerceAtLeast(0)
        var taken = 0
        while (taken < older.size) {
            val cost = older[taken].content.length + older[taken].role.length + 4
            if (taken > 0 && cost > room) break
            room -= cost
            taken++
        }
        return older.subList(0, taken).toList() to older.subList(taken, older.size).toList()
    }
}
