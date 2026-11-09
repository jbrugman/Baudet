package dev.mule.session

/**
 * The arithmetic of compaction, apart from the call that performs it: which
 * turns get folded, and the text handed to the model. Kept pure so folding the
 * wrong turns is a failing test rather than a lost conversation.
 */
object CompactionPlan {

    /**
     * The characters [source] wraps around a carried summary that are not the summary itself: the
     * "(previous summary) " prefix (19) and the blank line after it (2). [foldBatch] reserves these
     * next to the summary text so a batch measured against the same ceiling it is poured into never
     * overflows it once the wrapper is added.
     */
    private const val SUMMARY_HEADER_OVERHEAD = 21

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
     * The oldest turns that fit into one summarization call next to a summary already
     * [summaryChars] characters long, and the turns left over for the call after it. Batching
     * is what lets a conversation that is already past the backend's window still be folded:
     * the fold must never need the whole conversation to fit into a single call, or compacting
     * fails for exactly the conversation that needs it. Always takes at least one turn, so a
     * single oversized turn still advances the fold instead of stalling it; the caller keeps such
     * a turn verbatim rather than cutting it, since a cut tail never reaches the model.
     *
     * The room is measured against the finished [source], not just the turn texts: a carried
     * summary also drags in [SUMMARY_HEADER_OVERHEAD] characters of wrapper, so reserving the bare
     * summary text alone let a full batch push the finished source a few characters past the
     * ceiling. Reserving the header too keeps a fitting batch completely inside the ceiling.
     *
     * @return the batch to summarize now, and everything still to fold
     */
    fun foldBatch(older: List<Msg>, summaryChars: Int, maxChars: Int): Pair<List<Msg>, List<Msg>> {
        val reserved = if (summaryChars > 0) summaryChars + SUMMARY_HEADER_OVERHEAD else 0
        var room = (maxChars - reserved).coerceAtLeast(0)
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
