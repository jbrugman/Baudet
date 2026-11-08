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

    /** Keep the last [keepLast] turns verbatim, summarize everything before them. */
    fun compact(state: SessionState, keepLast: Int, onDelta: (String) -> Unit): String? {
        val splitAt = CompactionPlan.splitAt(state.messages.size, keepLast) ?: return state.summary
        val older = state.messages.subList(0, splitAt).toList()
        val source = CompactionPlan.source(state.summary, older)
        val turn = ResponsesClient(cfg).completeOnce(systemPrompt, source, 512, onDelta)
        if (!acceptsSummary(turn)) return state.summary
        val summary = turn.text.trim()
        state.summary = summary
        state.messages.subList(0, splitAt).clear()
        state.previousResponseId = null // the server session still holds the turns we dropped
        return summary
    }

    companion object {
        /**
         * Whether [turn] may replace the older turns. A summary the backend cut off -- a token
         * limit reached before it finished, reported through incompleteReason -- must never stand
         * in for the turns it would drop: those turns are gone the moment they are cleared, so
         * only a complete, non-blank summary is allowed to replace them.
         */
        internal fun acceptsSummary(turn: dev.mule.api.Turn): Boolean =
            turn.text.isNotBlank() && turn.incompleteReason == null
    }
}
