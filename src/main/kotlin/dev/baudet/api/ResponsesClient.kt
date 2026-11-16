package dev.baudet.api

import dev.baudet.config.AppConfig
import dev.baudet.session.Msg
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
        onReasoning: (String) -> Unit = {},
        token: CancellationToken = CancellationToken.NONE,
        image: String? = null
    ): Turn = call(
        buildRequest(system, history, userText, previousResponseId, tools, image), onDelta, onReasoning, token
    )

    /** One-off call that is not stored. Used by the compactor. */
    fun completeOnce(system: String, userText: String, maxTokens: Int, onDelta: (String) -> Unit): Turn {
        val body = buildJsonObject {
            put("model", cfg.model)
            if (system.isNotBlank()) put("instructions", system)
            put("stream", true)
            put("store", false)
            put("max_output_tokens", maxTokens)
            put("temperature", 0.2)
            // The fold deletes text, so it must not lose its summary to hidden thinking. A reasoning
            // model spends max_output_tokens on reasoning first and can leave the content channel
            // empty, which acceptsSummary then refuses as "empty". A summary is mechanical, so this
            // call always asks for it without reasoning. The ask is unconditional and NOT gated on
            // cfg.reasoningEffort: that field defaults to null, and a reasoning-by-default backend
            // (gpt-5 class) thinks even when it is unset -- exactly the case that comes back empty.
            // Gating on it skipped the fix in precisely the setup that needs it.
            put("reasoning", buildJsonObject { put("effort", "none") })
            // effort=none is not enough for the local backends this app targets: oMLX/vLLM keep a
            // thinking-by-default chat template, so Qwen still spends the budget on hidden reasoning
            // and returns an empty content channel -- the exact "nothing was compacted" the fold
            // reported. chat_template_kwargs is where those servers actually disable it (verified on
            // the resident model: reasoning emptied, summary content returned in full).
            put("chat_template_kwargs", buildJsonObject { put("enable_thinking", false) })
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
        tools: String?,
        image: String? = null
    ): JsonObject = buildJsonObject {
        put("model", cfg.model)
        if (system.isNotBlank()) put("instructions", system)
        put("stream", true)
        put("store", cfg.storeResponses)
        // 0 means the user asked the server to decide: sending 0 is not the same as sending
        // nothing -- it caps the answer at nothing -- so the field is left out entirely.
        if (cfg.maxOutputTokens > 0) put("max_output_tokens", cfg.maxOutputTokens)
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
            if (image == null) {
                add(message("user", userText))
            } else {
                addJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        if (userText.isNotBlank()) addJsonObject {
                            put("type", "input_text")
                            put("text", userText)
                        }
                        addJsonObject {
                            put("type", "input_image")
                            put("image_url", image)
                        }
                    })
                }
            }
        })
    }

    private fun message(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun call(
        body: JsonObject,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit = {},
        token: CancellationToken = CancellationToken.NONE
    ): Turn {
        val acc = TurnAccumulator()
        for (ev in Http(cfg).sse("/responses", body.toString(), token)) {
            val d = acc.feed(ev)
            d?.text?.let { onDelta(it) }
            d?.reasoning?.let { onReasoning(it) }
        }
        return acc.finish()
    }
}
