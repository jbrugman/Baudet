package dev.baudet.session

import dev.baudet.api.ResponsesClient
import dev.baudet.api.Turn
import dev.baudet.config.AppConfig

/**
 * Client-side compaction: there is no /responses/compact endpoint on this
 * backend (verified against its OpenAPI paths), so the client summarizes and
 * the result replaces the older turns. Exactly what Codex itself does. Runs as
 * a separate, cheap call with store=false.
 *
 * [summarize] is the single summarization call this class makes, injected so the lossless
 * guarantee -- that no turn is ever cleared on a summary the model did not fully read -- can be
 * driven through the real [compact] flow without a backend. It defaults to the cheap, unstored
 * call the fold is meant to make.
 */
class Compactor(
    private val cfg: AppConfig,
    private val windowTokens: Int? = null,
    private val summarize: (source: String, onDelta: (String) -> Unit) -> Turn =
        { source, onDelta ->
            ResponsesClient(cfg).completeOnce(SUMMARY_SYSTEM_PROMPT, source, SUMMARY_TOKENS, onDelta)
        }
) {

    /**
     * Keep the last [keepLast] turns verbatim, summarize everything before them. The fold runs
     * in batches from the oldest turn forward, each batch summarized on top of the summary the
     * batch before it produced, so the conversation is never handed over whole. That matters
     * because the conversation that needs folding is the one that no longer fits a call: sent
     * in one piece it fails with the same context error it was meant to fix, which is exactly
     * how a session gets stranded. Only turns whose summary the backend proved complete are
     * cleared, so a refused batch leaves those turns verbatim and the fold stops there.
     *
     * The fold never cuts a message to make it fit. A turn larger than one summarization call
     * can carry would have to be cut in half, and the cut tail would never reach the model yet
     * the whole turn would be cleared once its summary was accepted -- losing text it never read.
     * The fold therefore stops at such a turn and keeps it, and everything after it, verbatim;
     * the batches already proved complete still fold. Clearing a turn the model only half read
     * is a silent loss, and losing the user's words is the one thing compacting must never do.
     *
     * @return the running summary, or null when nothing at all was folded
     */
    fun compact(state: SessionState, keepLast: Int, onDelta: (String) -> Unit): String? {
        val splitAt = CompactionPlan.splitAt(state.messages.size, keepLast) ?: return state.summary
        val maxChars = maxSourceChars()
        var summary = state.summary
        var remaining = state.messages.subList(0, splitAt).toList()
        var folded = 0
        var stop = false
        while (!stop && remaining.isNotEmpty()) {
            val (batch, rest) = CompactionPlan.foldBatch(remaining, summary?.length ?: 0, maxChars)
            val source = CompactionPlan.source(summary, batch)
            // [foldBatch] keeps a fitting batch's complete text inside maxChars, so a source longer
            // than the ceiling means this batch is a single turn too big for one call. The only way
            // to send it would be to cut its tail, which loses text the model would never read once
            // the whole turn were cleared. End the fold here and keep that turn verbatim instead.
            if (source.length > maxChars) {
                stop = true
                break
            }
            // A batch that cannot be summarized at all (backend down, stream lost) must not undo
            // the batches already folded: stop here and keep what was proved. The very first
            // failure still goes out as an exception, or an outage reads as "returned nothing".
            val turn = try {
                summarize(source, onDelta)
            } catch (e: Exception) {
                if (folded == 0) throw e
                stop = true
                null
            }
            if (turn != null && acceptsSummary(turn)) {
                summary = turn.text.trim()
                folded += batch.size
                remaining = rest
            } else {
                stop = true
            }
        }
        // Nothing was proved complete, so nothing is dropped: the session stays as it was.
        if (folded == 0) return null
        state.summary = summary
        state.messages.subList(0, folded).clear()
        state.previousResponseId = null // the server session still holds the turns we dropped
        return summary
    }

    /**
     * How much text one summarization call may carry. The estimate is ~3 chars per token, as in
     * [SessionState.estimateTokens]; the hard ceiling is what actually protects the request,
     * because the configured budget is the user's guess and can itself sit at the model's
     * window. At this ceiling a call carries roughly sixteen thousand tokens.
     *
     * A budget typed in Settings lowers the ceiling but never sets it. It used to: the field reads 0
     * when the app is left to derive it, and a ceiling of 0 reached the backend uncapped, because
     * [CompactionPlan.source] reads a non-positive ceiling as "no ceiling" while
     * [CompactionPlan.foldBatch] always takes at least one turn. A conversation whose oldest turn was
     * a large tool observation was therefore folded in one piece and refused with the very context
     * error the Compact button exists to clear, which is how a session gets stranded twice.
     *
     * The model's real window bounds it too, and that bound is not the same kind of number: a 4k
     * model cannot carry 48k characters, so on such a model the default ceiling is itself the bug.
     * [windowTokens] is handed in by the caller rather than looked up, so fold and turn guard cannot
     * end up measuring against two different windows.
     */
    internal fun maxSourceChars(): Int =
        minOf(
            MAX_SOURCE_CHARS,
            (cfg.contextBudgetTokens * 3).takeIf { it > 0 } ?: MAX_SOURCE_CHARS,
            windowTokens?.takeIf { it > 0 }?.let { it / SOURCE_WINDOW_DIVISOR * 3 } ?: Int.MAX_VALUE
        ).coerceAtLeast(MIN_SOURCE_CHARS)

    companion object {
        /** Model-facing, so always English: this is what tells the model to summarize. */
        const val SUMMARY_SYSTEM_PROMPT =
            "Summarise the following conversation as compactly as possible. Keep: file names, " +
                "symbols, decisions taken, open points, and every factual conclusion. " +
                "No commentary, no preamble."

        /** Output bound for one summary call: a summary is meant to be short. */
        private const val SUMMARY_TOKENS = 512

        /** Ceiling on the text of one summarization call, in characters. */
        private const val MAX_SOURCE_CHARS = 48_000

        /**
         * Floor under a typed budget. Below this a batch is a fragment of one turn, and a summary
         * cut from a fragment still reports itself complete -- so it would be accepted and the turns
         * it never read would be cleared. A budget that small is a mistake, not an instruction.
         *
         * It also wins over a window too small to hold a batch, on purpose: failing with a refusal
         * is recoverable, summarizing a fragment and then clearing the turns it never read is not.
         */
        private const val MIN_SOURCE_CHARS = 4_000

        /**
         * A summarization call may use half the model's window. The other half has to hold the
         * summary it writes, the instructions above it and whatever the backend counts on top --
         * and unlike a chat turn the fold has no second chance to shrink: a batch that overflows is
         * a batch that is not folded, on the one conversation that needed folding most.
         */
        private const val SOURCE_WINDOW_DIVISOR = 2

        /**
         * Whether [turn] may replace the older turns. Those turns are gone the moment they are
         * cleared, so only an answer the backend proved it finished is allowed to replace them.
         * A summary cut off at a token limit reports an incompleteReason, and a stream that
         * closed without a terminal event never reaches a completed state, so both are refused.
         */
        internal fun acceptsSummary(turn: Turn): Boolean =
            turn.text.isNotBlank() && turn.incompleteReason == null && turn.completed
    }
}
