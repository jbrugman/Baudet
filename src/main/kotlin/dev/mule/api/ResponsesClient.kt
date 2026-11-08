package dev.mule.api

import dev.mule.config.AppConfig
import dev.mule.session.Msg
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Primary transport: the Responses API with a server-side session through
 * previous_response_id. Deviating backends are tolerated by design, and the
 * event census on Turn makes a silently missed delta visible instead of lost.
 */
class ResponsesClient(private val cfg: AppConfig) {

    fun turn(
        system: String,
        history: List<Msg>,
        userText: String,
        previousResponseId: String?,
        tools: String? = null,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit = {}
    ): Turn = call(buildRequest(system, history, userText, previousResponseId, tools), onDelta, onReasoning)

    /** One-off call that is not stored. Used by the compactor. */
    fun completeOnce(system: String, userText: String, maxTokens: Int, onDelta: (String) -> Unit): Turn {
        val body = buildJsonObject {
            put("model", cfg.model)
            if (system.isNotBlank()) put("instructions", system)
            put("stream", true)
            put("store", false)
            put("max_output_tokens", maxTokens)
            put("temperature", 0.2)
            put("input", buildJsonArray {
                addJsonObject { put("role", "user"); put("content", userText) }
            })
        }
        return call(body, onDelta)
    }

    internal fun buildRequest(
        system: String,
        history: List<Msg>,
        userText: String,
        previousResponseId: String?,
        tools: String?
    ): JsonObject = buildJsonObject {
        put("model", cfg.model)
        if (system.isNotBlank()) put("instructions", system)
        put("stream", true)
        put("store", cfg.storeResponses)
        put("max_output_tokens", cfg.maxOutputTokens)
        put("temperature", cfg.temperature)
        cfg.topP?.let { put("top_p", it) }
        cfg.reasoningEffort?.takeIf { it.isNotBlank() }?.let { e ->
            put("reasoning", buildJsonObject { put("effort", e) })
        }
        // Offer the enabled tools as native functions so a chat-tuned model can answer
        // with a function_call instead of a text protocol it may not follow. A tools
        // string that will not parse as an array is skipped rather than sent half-formed.
        tools?.let { t ->
            (json.parseToJsonElement(t) as? JsonArray)?.let { put("tools", it) }
        }
        if (previousResponseId != null) put("previous_response_id", previousResponseId)
        put("input", buildJsonArray {
            // The server already has the history when previous_response_id works;
            // otherwise replay it from the client.
            if (previousResponseId == null) {
                history.forEach { m ->
                    if (m.role != "system") add(message(m.role, m.content))
                }
            }
            add(message("user", userText))
        })
    }

    private fun message(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun call(
        body: JsonObject,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit = {}
    ): Turn {
        val acc = TurnAccumulator()
        for (ev in Http(cfg).sse("/responses", body.toString())) {
            val d = acc.feed(ev)
            d?.text?.let { onDelta(it) }
            d?.reasoning?.let { onReasoning(it) }
        }
        return acc.finish()
    }
}
