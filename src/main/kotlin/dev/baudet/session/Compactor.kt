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
            ResponsesClient(cfg).completeOnce(
                SUMMARY_SYSTEM_PROMPT, source, summaryTokens(windowTokens), onDelta
            )
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
     * The fold never clears text the model did not read. A turn larger than one summarization
     * call is not cut and cleared behind a summary of only its head -- that would lose the tail
     * silently. It is instead read end to end across consecutive calls, each slice summarized on
     * top of the running summary, and only once every slice the model actually saw came back
     * accepted is the whole turn cleared. So a turn that overflows one call folds instead of
     * stranding the fold, while a slice that comes back refused or fails leaves that turn, and
     * everything after it, verbatim -- and the batches already proved complete still fold.
     *
     * @return what the fold did, so the caller can report the truth rather than guess at it
     */
    fun compact(state: SessionState, keepLast: Int, onDelta: (String) -> Unit): CompactionOutcome {
        val splitAt = CompactionPlan.splitAt(state.messages.size, keepLast)
            ?: return CompactionOutcome.NothingToFold
        val maxChars = maxSourceChars()
        // The bound this fold asks its summaries to fit in, derived from the same window as
        // maxSourceChars and read once here so a refusal can name the number the answer was
        // cut at. The default [summarize] derives it from the same input, so the two cannot
        // disagree about what was sent.
        val cap = summaryTokens(windowTokens)
        var summary = state.summary
        var remaining = state.messages.subList(0, splitAt).toList()
        var folded = 0
        var stop = false
        // The answer that ended the fold without being accepted, when an answer is what ended it.
        var refused: Turn? = null
        while (!stop && remaining.isNotEmpty()) {
            val (batch, rest) = CompactionPlan.foldBatch(remaining, summary?.length ?: 0, maxChars)
            val source = CompactionPlan.source(summary, batch)
            if (source.length <= maxChars) {
                // A batch that fits one call: summarize it whole, exactly as before. A batch that
                // cannot be summarized at all (backend down, stream lost) must not undo the batches
                // already folded, so a failure stops here and keeps what was proved -- while the
                // very first failure still surfaces as an exception, so a backend that is down reads
                // as an outage and never as a summary that was merely refused.
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
                    if (turn != null) refused = turn
                    stop = true
                }
                continue
            }
            // The source overflows the ceiling, which -- because [foldBatch] carries only one turn
            // past it -- means this batch is a single turn larger than one call can hold. The old
            // fold stranded here and kept that turn verbatim, leaving the Compact button folding
            // nothing on exactly the session that needed it. Instead, fold THIS turn across
            // consecutive calls: read it in consecutive slices, summarizing each on top of the
            // running summary, and clear the turn only once EVERY slice the model actually read came
            // back accepted. No text is ever cleared that the model did not read -- the slices tile
            // the turn end to end, so their summaries together account for the whole of it.
            val oversized = batch.singleOrNull()
                ?: run { stop = true; break } // [foldBatch] overflows only on one turn; defensive
            var read = 0
            var whole = true
            while (read < oversized.content.length) {
                val room = CompactionPlan.contentRoom(summary?.length ?: 0, oversized.role.length, maxChars)
                if (room <= 0) {
                    whole = false // even a slice cannot fit beside the running summary: stop, keep it
                    break
                }
                val upto = (read + room).coerceAtMost(oversized.content.length)
                val piece = Msg(oversized.role, oversized.content.substring(read, upto))
                val pieceSource = CompactionPlan.source(summary, listOf(piece))
                val turn = try {
                    summarize(pieceSource, onDelta)
                } catch (e: Exception) {
                    if (folded == 0) throw e
                    stop = true
                    whole = false
                    break
                }
                if (turn != null && acceptsSummary(turn)) {
                    summary = turn.text.trim()
                    read = upto
                } else {
                    if (turn != null) refused = turn
                    whole = false
                    break
                }
            }
            if (whole) {
                folded += batch.size
                remaining = rest
            } else {
                stop = true
            }
        }
        // Nothing was proved complete, so nothing is dropped: the session stays as it was. Which
        // of the two reasons did it is the difference between a call that never happened and an
        // answer that could not be trusted, and the fold knows which it was: an unaccepted answer
        // arrived, otherwise the size guard stopped the fold before it sent anything at all.
        if (folded == 0) {
            val refusal = refused
            return if (refusal != null) CompactionOutcome.SummaryRefused(refusalReason(refusal, cap))
            else CompactionOutcome.OversizedTurn(maxChars)
        }
        // Non-null exactly when a batch was accepted, which is exactly when folded is above zero,
        // so there is no null to plan for here -- and inventing a summary to report would be the
        // one way this method could still lie about a fold.
        val proved = summary!!
        state.summary = proved
        state.messages.subList(0, folded).clear()
        state.previousResponseId = null // the server session still holds the turns we dropped
        return CompactionOutcome.Compacted(proved)
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

        /**
         * The output bound for one summarization call. It used to be the constant 512, which on a
         * reasoning model is not a bound on the summary but on the summary *and* its thinking: this
         * call sends no `reasoning` field, so whatever the model thinks is spent inside whichever
         * cap is sent, and a model that thinks a little longer ends its summary mid sentence. An
         * ended summary is refused by [acceptsSummary], so the user saw a Compact button that folded
         * nothing on exactly the session that needed it -- correct, since clearing text the model
         * never finished reading would have been the worse bug, but a bound nobody can reach is its
         * own defect. A model that reasons about every question, however simple, is the case that
         * pays this first.
         *
         * Measured on the local backend against a batch at the [maxSourceChars] ceiling: Qwen3.8
         * finished the summary in 238 output tokens, DeepSeek-V4-Flash in 317 of which 207 was
         * thinking. Both fit inside 512, but left less than a screenful of headroom -- headroom is
         * precisely what a model that thinks longer spends, and on a longer or denser transcript
         * that thinking grows until it eats the whole bound and the content channel comes back
         * empty, which [acceptsSummary] refuses as "empty" and the fold then does nothing. The
         * floor is therefore raised well past the measured figures so thinking and summary share
         * the bound with room left; a longer cap costs nothing here, since it is a ceiling not an
         * ask, and the summary replaces the text it summarizes.
         *
         * The window bounds the ask, and that bound is the arithmetic already in [maxSourceChars]:
         * a batch may take half the window, so a summary taking a quarter leaves a quarter for the
         * instructions above them and whatever else the backend counts. Ceilings win over windows,
         * because a summary is meant to be short and a model that rambles into a fifth of the window
         * is a model worth paying for only once per fold.
         */
        internal fun summaryTokens(windowTokens: Int?): Int =
            minOf(
                MAX_SUMMARY_TOKENS,
                windowTokens?.takeIf { it > 0 }?.let { it / SUMMARY_WINDOW_DIVISOR } ?: MAX_SUMMARY_TOKENS
            ).coerceAtLeast(MIN_SUMMARY_TOKENS)

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

        /** A summary summarizes: a long answer is a model going wrong, so the ask is capped. */
        private const val MAX_SUMMARY_TOKENS = 2_048

        /**
         * The floor under the fold's output bound, sized so a reasoning model's thinking and its
         * summary share the bound with room left. It used to be 512, which the measured thinking
         * (207 tokens) fit inside only because that batch was small; on a denser transcript the
         * thinking alone can pass 512 and the summary never starts, the exact empty-refusal this
         * floor exists to prevent. It stays a floor so a tiny window cannot starve the fold.
         */
        private const val MIN_SUMMARY_TOKENS = 1_536

        /** A summary may ask a quarter of the window; see [summaryTokens] for the arithmetic. */
        private const val SUMMARY_WINDOW_DIVISOR = 4

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

        /**
         * Why [acceptsSummary] refused [turn], in the backend's own terms and untranslated, so the
         * status line can name what arrived instead of claiming nothing did. The three refusals are
         * different problems worth telling apart: an empty answer is a model that did not answer, an
         * incomplete reason is a summary cut off at an output bound that travels with the reason,
         * since a truncation that does not name its own ceiling tells nobody what to raise, and a stream
         * that never reached a completion event is a backend this app cannot prove anything about --
         * the last of those is also the one that would otherwise stay invisible forever, because a
         * chat turn forgives a missing terminal event while a fold, which deletes text, cannot.
         */
        internal fun refusalReason(turn: Turn, cap: Int): String = when {
            turn.text.isBlank() -> "empty"
            turn.incompleteReason != null -> "incomplete: " + turn.incompleteReason + " ($cap)"
            else -> "no response.completed, got " + (turn.finalEvent ?: "a stream that closed silently")
        }
    }
}
