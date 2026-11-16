package dev.baudet.session

import dev.baudet.thread.Item

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
    /**
     * The rich transcript rows shown for this conversation, kept so a reload rebuilds the
     * cards instead of flattening them. A display projection independent of [messages]:
     * compaction trims the model context in [messages] but never this audit trail.
     */
    val items: MutableList<Item> = mutableListOf()
    /**
     * The store sequence number of the oldest row currently in [items], or null when [items] already
     * reaches the start of the conversation. Non-null means older rows exist that this view has not
     * read yet. Writes append only the new tail, so a partially loaded view never pulls those
     * older rows back -- they stay untouched in the store until the scroll path asks for them.
     */
    var oldestLoadedSeq: Int? = null
    /** Whether the store still holds rows older than [items]. */
    val hasMoreOlder: Boolean get() = (oldestLoadedSeq ?: 0) > 0
    /**
     * How many of this conversation's model-context turns are already rows in the store. The
     * store is the source of truth and grows by append; this view mirrors it and tracks the
     * boundary so [persistTo] writes only the newer tail rather than rewriting every row. It is
     * set from the full read on open and from a rewrite only when compaction renumbers the head.
     */
    var persistedMessages: Int = 0
    /**
     * The itemId of the newest transcript row already in the store, or null when none is. The
     * item trail is append-only, so persisting appends the rows after this marker rather than
     * rewriting the trail. Searched by [List.indexOfLast], so prepending an older page mid-turn
     * (which adds only already-stored rows) leaves the boundary at the genuine newest row.
     */
    var persistedItemsTailId: String? = null
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
