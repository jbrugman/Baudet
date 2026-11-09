package dev.baudet.session

/**
 * One conversation. previousResponseId is the wire to the server-side session;
 * null means we send the history ourselves.
 */
class SessionState(
    var sessionId: String,
    var model: String,
    var previousResponseId: String?,
    val messages: MutableList<Msg> = mutableListOf()
) {
    /** Summary after compaction; replaces the older turns in the prompt. */
    var summary: String? = null

    /** The project this conversation belongs to; null means the Unassigned bucket. */
    var projectId: String? = null

    /** Rough heuristic (~3 chars/token). No tokenizer: those are model-specific. */
    fun estimateTokens(): Long = estimateTokensOf(messages, summary?.length ?: 0)

    /**
     * The kept turns for client-side replay. The summary is deliberately NOT folded in
     * here as a system row: the request builder and the tool transcript both drop system
     * rows, so folding it in would silently lose it. It rides in the system instructions
     * instead, which every path already sends; see AppState.instructionsFor.
     */
    fun promptHistory(): List<Msg> = messages.toList()

    fun transcript(): String = buildString {
        messages.forEach { m -> append(m.role).append(": ").append(m.content).append("\n\n") }
    }

    companion object {
        /**
         * The same rough estimate for any message list: ~3 chars/token plus a small per-turn
         * allowance for the role and the chat-template wrapper. Shared on purpose: the status
         * line, the pre-send guard and the in-loop ceiling all measure one way, so a request is
         * never stopped or allowed by a number that disagrees with the one shown to the user.
         */
        fun estimateTokensOf(messages: List<Msg>, summaryChars: Int = 0): Long {
            var chars = summaryChars
            messages.forEach { chars += it.content.length + it.role.length + 8 }
            return chars / 3L
        }
    }
}
