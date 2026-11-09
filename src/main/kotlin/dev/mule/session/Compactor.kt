package dev.mule.session

import dev.mule.api.ResponsesClient
import dev.mule.config.AppConfig

/**
 * Client-side compaction: there is no /responses/compact endpoint on this
 * backend (verified against its OpenAPI paths), so the client summarizes and
 * the result replaces the older turns. Exactly what Codex itself does. Runs as
 * a separate, cheap call with store=false.
 */
class Compactor(private val cfg: AppConfig) {

    /** Model-facing, so always English: this is what tells the model to summarize. */
    private val systemPrompt =
        "Summarise the following conversation as compactly as possible. Keep: file names, " +
            "symbols, decisions taken, open points, and every factual conclusion. " +
            "No commentary, no preamble."

    /**
     * Keep the last [keepLast] turns verbatim, summarize everything before them. The fold runs
     * in batches from the oldest turn forward, each batch summarized on top of the summary the
     * batch before it produced, so the conversation is never handed over whole. That matters
     * because the conversation that needs folding is the one that no longer fits a call: sent
     * in one piece it fails with the same context error it was meant to fix, which is exactly
     * how a session gets stranded. Only turns whose summary the backend proved complete are
     * cleared, so a refused batch leaves those turns verbatim and the fold stops there.
     *
     * @return the running summary, or null when nothing at all was folded
     */
    fun compact(state: SessionState, keepLast: Int, onDelta: (String) -> Unit): String? {
        val splitAt = CompactionPlan.splitAt(state.messages.size, keepLast) ?: return state.summary
        val maxChars = maxSourceChars()
        val client = ResponsesClient(cfg)
        var summary = state.summary
        var remaining = state.messages.subList(0, splitAt).toList()
        var folded = 0
        var stop = false
        while (!stop && remaining.isNotEmpty()) {
            val (batch, rest) = CompactionPlan.foldBatch(remaining, summary?.length ?: 0, maxChars)
            val source = CompactionPlan.source(summary, batch, maxChars)
            // A batch that cannot be summarized at all (backend down, stream lost) must not undo
            // the batches already folded: stop here and keep what was proved. The very first
            // failure still goes out as an exception, or an outage reads as "returned nothing".
            val turn = try {
                client.completeOnce(systemPrompt, source, SUMMARY_TOKENS, onDelta)
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
     */
    private fun maxSourceChars(): Int =
        (cfg.contextBudgetTokens * 3).coerceAtMost(MAX_SOURCE_CHARS)

    companion object {
        /** Output bound for one summary call: a summary is meant to be short. */
        private const val SUMMARY_TOKENS = 512

        /** Ceiling on the text of one summarization call, in characters. */
        private const val MAX_SOURCE_CHARS = 48_000

        /**
         * Whether [turn] may replace the older turns. Those turns are gone the moment they are
         * cleared, so only an answer the backend proved it finished is allowed to replace them.
         * A summary cut off at a token limit reports an incompleteReason, and a stream that
         * closed without a terminal event never reaches a completed state, so both are refused.
         */
        internal fun acceptsSummary(turn: dev.mule.api.Turn): Boolean =
            turn.text.isNotBlank() && turn.incompleteReason == null && turn.completed
    }
}
