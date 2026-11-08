package dev.mule.session

import kotlinx.serialization.Serializable

@Serializable
data class Msg(
    val role: String,
    val content: String,
    val responseId: String? = null,
    val promptTokens: Long? = null
)

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

    /** Rough heuristic (~4 chars/token). No tokenizer: those are model-specific. */
    fun estimateTokens(): Long {
        var chars = summary?.length ?: 0
        messages.forEach { chars += it.content.length + it.role.length + 8 }
        return chars / 4L
    }

    /** What actually goes into the prompt during client-side replay. */
    fun promptHistory(): List<Msg> {
        val s = summary
        return if (s == null) messages.toList()
        // Model-facing: English regardless of the UI language.
        else listOf(Msg("system", "Summary of the earlier conversation:\n$s")) + messages
    }

    fun transcript(): String = buildString {
        messages.forEach { m -> append(m.role).append(": ").append(m.content).append("\n\n") }
    }
}

object SessionIds {
    fun new(): String =
        "s-" + java.util.UUID.randomUUID().toString().substring(0, 8)
}
