package dev.mule.store

import dev.mule.session.Msg

interface TranscriptStore {
    /** projectId null places the session in the Unassigned bucket; null leaves an existing link untouched on a fresh row only. */
    fun upsertSession(id: String, model: String?, prevResponseId: String?, lastSeq: Int, projectId: String? = null)
    fun latestSessionId(limit: Int): List<String>
    fun messages(sessionId: String): List<Msg>
    fun previousResponseId(sessionId: String): String?
    fun model(sessionId: String): String?
    fun rewriteMessages(sessionId: String, msgs: List<Msg>)
    /** Delete one conversation and its messages; other sessions and files on disk are untouched. */
    fun deleteSession(id: String)
    /** Rename one conversation; a blank title reverts to the derived first-line title. */
    fun renameSession(id: String, title: String)
}
