package dev.mule.tool

import dev.mule.session.Msg

/**
 * Prepares a running transcript for one backend call. The newest message is the current
 * input; what is replayed before it is scrubbed so that the control tokens a chat-tuned
 * model sometimes leaks can never be imitated from history and never reach the panel.
 * Every prior turn is replayed, assistant turns included, so a run keeps its working
 * context when previous_response_id is off; scrubbing covers assistant text too, so
 * replaying it cannot re-feed the leakage this stops.
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
            .map { it.copy(content = scrub(it.content)) }
        return prior to userText
    }

    /**
     * The current input for one backend call. Without a server session the newest message
     * alone is the input and the rest replays. With a server session the backend already
     * holds everything up to the last assistant turn, so the input is every message the loop
     * appended since then -- usually several tool observations from one model turn -- joined so
     * none is dropped; before any assistant turn the newest stands alone.
     */
    fun currentInput(history: List<Msg>, underServerSession: Boolean): String {
        if (!underServerSession) return scrub(history.lastOrNull()?.content ?: "")
        val lastAssistant = history.indexOfLast { it.role == "assistant" }
        val tail = when {
            lastAssistant >= 0 -> history.subList(lastAssistant + 1, history.size)
            history.isNotEmpty() -> listOf(history.last())
            else -> emptyList()
        }
        val joined = tail.joinToString("\n\n") { scrub(it.content) }
        return joined.ifBlank { scrub(history.lastOrNull()?.content ?: "") }
    }
}
