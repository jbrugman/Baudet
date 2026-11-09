package dev.mule.session

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
    fun estimateTokens(): Long {
        var chars = summary?.length ?: 0
        messages.forEach { chars += it.content.length + it.role.length + 8 }
        return chars / 3L
    }

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
}
