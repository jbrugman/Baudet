package dev.baudet.session

import dev.baudet.config.ContextStrategy

/**
 * Bounds the window of conversation handed to one model call, without touching the stored
 * transcript. This is the LMStudio idea: the history the user sees stays whole, and only the copy
 * that goes over the wire is cut so it fits the model.
 *
 * Two trimming shapes, chosen by [ContextStrategy], plus a refusal:
 *  - [ContextStrategy.RecentMessages] keeps only the last [trim] window -- the newest turn plus the
 *    [keepRecent] turns before it -- then fits that tail to the budget. Sending the whole recent
 *    exchange, rather than a pinned oldest turn with the middle missing, is what keeps a local model
 *    on task instead of drifting back to a subject the conversation already left.
 *  - [ContextStrategy.RollingWindow] fills the window as full as the budget allows and rolls the
 *    oldest turns off the front, keeping the newest contiguous tail.
 *  - [ContextStrategy.StopAtLimit] is left whole here; the guard refused anything oversized before it
 *    could reach this code.
 *
 * The current turn -- the last message -- is always kept, and the system prompt cannot be lost
 * because it never lives in this list: it rides in the instructions of the request. Cost per message
 * mirrors [SessionState.estimateTokensOf] so the trim agrees with the number the status bar shows.
 */
object ContextTrimmer {

    /**
     * The window [strategy] asks for: the whole [history] under [ContextStrategy.StopAtLimit] -- the
     * guard refused anything oversized before it could reach here -- otherwise the bounded window from
     * [trim]. This is the single place the whole-versus-trimmed choice lives, so the one-shot turn and
     * the tool loop cannot disagree about how large a request may be. [keepRecent] is the number of
     * turns [ContextStrategy.RecentMessages] keeps; it is ignored by the other strategies.
     */
    fun bound(
        history: List<Msg>,
        strategy: ContextStrategy,
        budgetTokens: Long,
        keepRecent: Int = Int.MAX_VALUE
    ): List<Msg> =
        if (strategy == ContextStrategy.StopAtLimit) history
        else trim(history, strategy, budgetTokens, keepRecent)

    fun trim(
        history: List<Msg>,
        strategy: ContextStrategy,
        budgetTokens: Long,
        keepRecent: Int = Int.MAX_VALUE
    ): List<Msg> {
        if (budgetTokens <= 0L || history.isEmpty()) return history
        val budgetChars = budgetTokens * 3L
        // RecentMessages caps how far back the window reaches by count; RollingWindow reaches as far
        // as the budget allows. Both then fit the chosen window to the token budget the same way.
        val window = when (strategy) {
            ContextStrategy.RecentMessages -> lastN(history, keepRecent)
            ContextStrategy.RollingWindow -> history
            ContextStrategy.StopAtLimit -> return history
        }
        return fit(window, budgetChars)
    }

    /** The last [keepRecent] messages, at least one, or the whole list when it is already that short. */
    private fun lastN(history: List<Msg>, keepRecent: Int): List<Msg> =
        if (keepRecent in 1 until history.size) history.takeLast(keepRecent) else history

    /**
     * [window] reduced to what fits [budgetChars]: the newest turn always goes, older turns roll off
     * the front, and a single oversized newest turn is clamped rather than dropped so the request the
     * model must react to is never lost.
     *
     * A single message that alone busts the window is kept as a head slice that fits and labelled as
     * incomplete: truncating one observation is safe where dropping the whole turn the model has to
     * react to is not, and the earlier turns fall away because that oversized turn fills the window.
     */
    private fun fit(window: List<Msg>, budgetChars: Long): List<Msg> {
        val last = window.last()
        val lastCost = costChars(last)
        if (lastCost > budgetChars) return listOf(clampToWindow(last, budgetChars))
        val prior = window.dropLast(1)
        // A single message that already fits is the whole window, so there is nothing older to keep.
        if (prior.isEmpty()) return listOf(last)
        val room = budgetChars - lastCost
        return rolling(prior, room) + last
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
