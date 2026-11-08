package dev.mule.session

import dev.mule.api.ResponsesClient
import dev.mule.config.AppConfig

/**
 * Client-side compaction: there is no /responses/compact endpoint on this
 * backend (verified against its OpenAPI paths), so the client summarises and
 * the result replaces the older turns. Exactly what Codex itself does. Runs as
 * a separate, cheap call with store=false.
 */
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

class Compactor(private val cfg: AppConfig) {

    /** Model-facing, so always English: this is what tells the model to summarise. */
    private val systemPrompt =
        "Summarise the following conversation as compactly as possible. Keep: file names, " +
            "symbols, decisions taken, open points, and every factual conclusion. " +
            "No commentary, no preamble."

    /** Keep the last [keepLast] turns verbatim, summarise everything before them. */
    fun compact(state: SessionState, keepLast: Int, onDelta: (String) -> Unit): String? {
        val splitAt = CompactionPlan.splitAt(state.messages.size, keepLast) ?: return state.summary
        val older = state.messages.subList(0, splitAt).toList()
        val source = CompactionPlan.source(state.summary, older)
        val turn = ResponsesClient(cfg).completeOnce(systemPrompt, source, 512, onDelta)
        val summary = turn.text.trim()
        if (summary.isBlank()) return state.summary
        state.summary = summary
        state.messages.subList(0, splitAt).clear()
        state.previousResponseId = null // the server session still holds the turns we dropped
        return summary
    }
}
