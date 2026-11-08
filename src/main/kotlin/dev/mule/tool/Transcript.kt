package dev.mule.tool

import dev.mule.session.Msg

/**
 * Prepares a running transcript for one backend call. The newest message is the current
 * input; what is replayed before it is scrubbed so that the control tokens a chat-tuned
 * model sometimes leaks can never be imitated from history and never reach the panel.
 * Only prior user messages are replayed, never the assistant's own turns: with
 * previous_response_id off the backend still needs the human text, and replaying the
 * assistant turns again only re-feeds the very leakage this stops.
 */
object Transcript {

    /**
     * One model's control token, e.g. an end-of-turn marker wrapped in the special-tuple
     * form. Built from code points so the literal token never appears in this source.
     */
    private val special = Regex("\u003c\u005c\u007c[^\u007c\u003e]+\u005c\u007c\u003e")

    /** Removes leaked control tokens from any text; leaves ordinary prose untouched. */
    fun scrub(text: String): String = special.replace(text, "")

    /** @return the sanitized prior messages to replay, and the current user input. */
    fun forCall(history: List<Msg>): Pair<List<Msg>, String> {
        val userText = scrub(history.lastOrNull()?.content ?: "")
        val prior = history.dropLast(1)
            .filter { it.role == "user" }
            .map { it.copy(content = scrub(it.content)) }
        return prior to userText
    }
}
