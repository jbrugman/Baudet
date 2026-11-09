package dev.mule.session

import dev.mule.config.ContextStrategy

/**
 * Bounds the window of conversation handed to one model call, without touching the stored
 * transcript. This is the LMStudio idea: the history the user sees stays whole, and only the copy
 * that goes over the wire is cut so it fits the model.
 *
 * Two shapes, chosen by [ContextStrategy]:
 *  - [ContextStrategy.TruncateAtMiddle] keeps the oldest turn as an anchor and the newest turns, and
 *    removes what is between them. That preserves how the conversation started while keeping the
 *    context it needs to answer now.
 *  - [ContextStrategy.RollingWindow] keeps only the newest contiguous tail.
 *
 * The current turn -- the last message -- is always kept, and the system prompt cannot be lost
 * because it never lives in this list: it rides in the instructions of the request. Cost per message
 * mirrors [SessionState.estimateTokensOf] so the trim agrees with the number the status bar shows.
 */
object ContextTrimmer {

    /**
     * The newest-window view of [history] that fits [budgetTokens]. A non-positive budget means the
     * window is unknown, so nothing is invented and [history] is returned untouched. When there is
     * nothing to remove the list is returned unchanged -- in particular no placeholder note is added.
     */
    fun trim(history: List<Msg>, strategy: ContextStrategy, budgetTokens: Long): List<Msg> {
        if (history.size <= 1 || budgetTokens <= 0L) return history
        val last = history.last()
        val prior = history.dropLast(1)
        val budgetChars = budgetTokens * 3L - costChars(last)
        if (budgetChars <= 0L) return listOf(last)
        val kept = when (strategy) {
            ContextStrategy.RollingWindow -> rolling(prior, budgetChars)
            ContextStrategy.TruncateAtMiddle -> anchored(prior, budgetChars)
            ContextStrategy.StopAtLimit -> prior
        }
        return kept + last
    }

    /** The newest contiguous tail that fits, oldest first, with a note standing in for what fell off. */
    private fun rolling(prior: List<Msg>, budgetChars: Long): List<Msg> {
        var used = 0L
        var first = prior.size
        while (first > 0) {
            val c = costChars(prior[first - 1])
            if (used + c > budgetChars) break
            used += c
            first--
        }
        return if (first == 0) prior else listOf(note(first)) + prior.subList(first, prior.size)
    }

    /** The first turn plus the newest tail, with the middle removed; the anchor is dropped if too big. */
    private fun anchored(prior: List<Msg>, budgetChars: Long): List<Msg> {
        var anchorCount = 0
        var used = 0L
        if (costChars(prior[0]) <= budgetChars / 2) {
            anchorCount = 1
            used = costChars(prior[0])
        }
        var tailStart = prior.size
        var tailUsed = 0L
        while (tailStart > anchorCount) {
            val c = costChars(prior[tailStart - 1])
            if (used + tailUsed + c > budgetChars) break
            tailUsed += c
            tailStart--
        }
        val dropped = tailStart - anchorCount
        val out = ArrayList<Msg>(prior.size + 1)
        repeat(anchorCount) { out.add(prior[it]) }
        if (dropped > 0) out.add(note(dropped))
        out.addAll(prior.subList(tailStart, prior.size))
        return out
    }

    // Model-facing, so English whatever the UI speaks: this text goes into the prompt.
    private fun note(dropped: Int): Msg = Msg(
        "user",
        "[$dropped earlier messages were dropped from this request to keep it inside the model's " +
            "context window. The omitted context is gone from this request; the turns shown are intact.]"
    )

    private fun costChars(m: Msg): Long = m.content.length + m.role.length + 8L
}
