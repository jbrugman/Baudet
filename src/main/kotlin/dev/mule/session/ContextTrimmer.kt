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
     * The window [strategy] asks for: the whole [history] under [ContextStrategy.StopAtLimit] --
     * the guard refused anything oversized before it could reach here -- otherwise the bounded
     * window from [trim]. This is the single place the whole-versus-trimmed choice lives, so the
     * one-shot turn and the tool loop cannot disagree about how large a request may be.
     */
    fun bound(history: List<Msg>, strategy: ContextStrategy, budgetTokens: Long): List<Msg> =
        if (strategy == ContextStrategy.StopAtLimit) history
        else trim(history, strategy, budgetTokens)

    fun trim(history: List<Msg>, strategy: ContextStrategy, budgetTokens: Long): List<Msg> {
        if (budgetTokens <= 0L || history.isEmpty()) return history
        val budgetChars = budgetTokens * 3L
        val last = history.last()
        val lastCost = costChars(last)
        // The newest turn is what the model has to react to, so it is always sent -- but never whole
        // when it alone busts the window. A single oversized tool observation kept in full would blow
        // the request it is the only content of, which trimming is meant to prevent; so it is clamped
        // to a head slice that fits, and the earlier turns drop, since the oversized turn already
        // fills the window. Truncating one observation is safe where dropping a whole turn is not: it
        // goes out labelled as incomplete, so the model knows the tail is missing.
        if (lastCost > budgetChars) return listOf(clampToWindow(last, budgetChars))
        val prior = history.dropLast(1)
        // A single message that already fits is the whole window, so there is nothing older to keep.
        if (prior.isEmpty()) return listOf(last)
        val room = budgetChars - lastCost
        val kept = when (strategy) {
            ContextStrategy.RollingWindow -> rolling(prior, room)
            ContextStrategy.TruncateAtMiddle -> anchored(prior, room)
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

    /**
     * [msg] cut from the front to a content length whose whole cost fits [budgetChars], with a
     * model-facing note appended. The note length is reserved in the arithmetic, so a message that
     * was larger than the window comes back costing exactly the window. Under a window smaller than
     * the note alone the note is all that can go out, which still tells the model honestly that what
     * it sees is only the start of the turn.
     */
    private fun clampToWindow(msg: Msg, budgetChars: Long): Msg {
        val room = (budgetChars - msg.role.length - 8L - TRUNCATION_NOTE.length)
            .coerceAtLeast(0L)
            .toInt()
        return msg.copy(content = msg.content.take(room) + TRUNCATION_NOTE)
    }

    // Model-facing, so English whatever the UI speaks: this text goes into the prompt.
    private fun note(dropped: Int): Msg = Msg(
        "user",
        "[$dropped earlier messages were dropped from this request to keep it inside the model's " +
            "context window. The omitted context is gone from this request; the turns shown are intact.]"
    )

    private fun costChars(m: Msg): Long = m.content.length + m.role.length + 8L

    /** Appended to a turn clamped by [clampToWindow]; the room it occupies is reserved before cutting. */
    private const val TRUNCATION_NOTE =
        "\n\n[... this message was truncated to fit the model's context window ...]"
}
