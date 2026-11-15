package dev.baudet.store

import dev.baudet.session.Msg
import dev.baudet.thread.Item

interface TranscriptStore {
    /**
     * projectId null places the session in the Unassigned bucket. summary carries the
     * compaction digest so it survives a restart; the MERGE overwrites it, so callers
     * pass the current summary on every upsert, not just after a compaction.
     */
    fun upsertSession(
        id: String,
        model: String?,
        prevResponseId: String?,
        lastSeq: Int,
        projectId: String? = null,
        summary: String? = null
    )

    /** The stored compaction summary for a session, or null when it has none. */
    fun summaryOf(sessionId: String): String?
    fun latestSessionId(limit: Int): List<String>
    fun messages(sessionId: String): List<Msg>
    fun previousResponseId(sessionId: String): String?
    fun model(sessionId: String): String?
    fun rewriteMessages(sessionId: String, msgs: List<Msg>)

    /**
     * The rich transcript rows -- command, file-change and tool-call cards, errors -- as a
     * faithful record of what the interface showed, so a reload rebuilds the cards rather
     * than flattening them to prose. Rows are stored by their [Item] type tag and read back in
     * insertion order; an undecodable row is dropped rather than failing the whole read, the
     * same tolerance the stored config gets. Reasoning is never stored, so it never returns.
     */
    fun items(sessionId: String): List<Item>
    /** Whole-rewrite a conversation's transcript rows, mirroring [rewriteMessages] on replace. */
    fun rewriteItems(sessionId: String, items: List<Item>)
    /** Delete one conversation and its messages; other sessions and files on disk are untouched. */
    fun deleteSession(id: String)
    /** Rename one conversation; a blank title reverts to the derived first-line title. */
    fun renameSession(id: String, title: String)
}
