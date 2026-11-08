package dev.mule.api

import dev.mule.config.AppConfig
import dev.mule.session.Msg
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class Usage(val prompt: Long?, val completion: Long?, val total: Long?)

data class Turn(
    val text: String,
    val responseId: String?,
    val usage: Usage?,
    /** the finish signal the backend actually sent, for diagnostics */
    val finalEvent: String?,
    /** payloads seen per event type, in first-seen order */
    val events: Map<String, Int> = emptyMap(),
    /** first raw payload per event type, truncated: what the wire really carried */
    val samples: Map<String, String> = emptyMap(),
    /** reasoning summary streamed alongside the answer, kept out of the prompt */
    val reasoning: String = "",
    /** why the backend stopped early, e.g. max_output_tokens; null when it finished */
    val incompleteReason: String? = null
)

/**
 * What one payload carried. Answer text and reasoning stay separate: reasoning
 * is shown to the user but must never re-enter the prompt or the transcript.
 */
class Delta(val text: String? = null, val reasoning: String? = null)

private const val NO_TYPE = "(no-type)"
private const val NO_JSON = "(not-json)"

/**
 * Reduces SSE payloads into one turn, without transport and without Swing,
 * because event-name matching is precisely where local backends deviate from
 * the OpenAI text. An unknown event is counted and sampled, never fatal: at
 * worst a delta is missed, and events plus samples still show what arrived.
 */
class TurnAccumulator(private val sampleLimit: Int = 700) {

    private val out = StringBuilder()
    private val reason = StringBuilder()
    val events = LinkedHashMap<String, Int>()
    val samples = LinkedHashMap<String, String>()

    var responseId: String? = null
        private set
    var usage: Usage? = null
        private set
    var finalEvent: String? = null
        private set
    var eventCount = 0
        private set
    var errorFromStream: String? = null
        private set
    var incompleteReason: String? = null
        private set

    /** @return what this payload carried, if anything: answer text, reasoning, or both */
    fun feed(ev: SseEvent): Delta? {
        eventCount++
        val o = try {
            json.parseToJsonElement(ev.data).jsonObject
        } catch (_: Exception) {
            record(NO_JSON, ev.data)
            return null
        }
        // data.type first, as the Responses API specifies it; then the event:
        // field, which some backends use as the only carrier of the name; then
        // object, which is all a chat.completion.chunk brings.
        val type = o.str("type") ?: ev.event ?: o.str("object") ?: NO_TYPE
        record(type, ev.data)
        val d = extract(type, o)
        d?.text?.let { out.append(it) }
        d?.reasoning?.let { reason.append(it) }
        return d
    }

    /** @return the finished turn, or throw if the stream errored or carried nothing */
    fun finish(): Turn {
        errorFromStream?.let { throw BackendException(200, "backend reported an error: $it") }
        if (eventCount == 0) {
            throw BackendException(
                0,
                "backend sent no SSE events on /responses " +
                    "(stream=true unsupported? base url without /v1?)"
            )
        }
        return Turn(
            text = out.toString(),
            responseId = responseId,
            usage = usage,
            finalEvent = finalEvent ?: "stream_end_after_$eventCount events",
            events = events.toMap(),
            samples = samples.toMap(),
            reasoning = reason.toString(),
            incompleteReason = incompleteReason
        )
    }

    private fun record(type: String, payload: String) {
        events[type] = (events[type] ?: 0) + 1
        if (!samples.containsKey(type)) samples[type] = payload.take(sampleLimit)
    }

    private fun extract(type: String, o: JsonObject): Delta? {
        val d = o.str("delta")
        when {
            type.endsWith("output_text.delta") -> return d?.let { Delta(text = it) }

            // Reasoning is the bulk of what a local reasoning model spends its
            // budget on. Discarding it leaves an empty answer that only reports
            // tokens burned, so it is captured and shown, never discarded.
            type.endsWith("reasoning_summary_text.delta") ||
            type.endsWith("reasoning_text.delta") -> return d?.let { Delta(reasoning = it) }

            type == "chat.completion.chunk" -> {
                val choice = o.arr("choices")?.firstOrNull() as? JsonObject
                val c = (choice?.obj("delta") ?: choice?.obj("message"))?.str("content")
                return c?.let { Delta(text = it) }
            }

            type == "error" -> {
                errorFromStream = o.toString()
                return null
            }
        }
        if (type.startsWith("response.")) absorbResponse(type, o)
        return null
    }

    /** Response-level bookkeeping: the id, the usage, and why the run ended. */
    private fun absorbResponse(type: String, o: JsonObject) {
        val r = o.obj("response") ?: o
        r.str("id")?.let { responseId = it }
        r.obj("usage")?.let { usage = parseUsage(it) }

        if (type == "response.completed" || type == "response.incomplete" ||
            type == "response.failed"
        ) {
            finalEvent = type
            if (type != "response.completed") {
                // Truncation must be named. "No answer" and "budget exhausted
                // before the answer started" look identical unless the reason is
                // surfaced, and only one of them is fixed by raising the budget.
                incompleteReason = r.obj("incomplete_details")?.str("reason")
                    ?: r.obj("error")?.str("code")
                    ?: r.obj("error")?.str("message")
                    ?: type
            }
        }

        // Tolerant fallback: a backend may skip deltas and deliver the finished
        // text only. Adopt it just when nothing streamed, so nothing counts twice.
        if (type.endsWith("output_text.done") && out.isEmpty()) {
            o.str("text")?.let { if (it.isNotEmpty()) out.append(it) }
        }
        if (type.endsWith("reasoning_summary_text.done") && reason.isEmpty()) {
            o.str("text")?.let { if (it.isNotEmpty()) reason.append(it) }
        }
    }

    private fun parseUsage(u: JsonObject): Usage =
        Usage(
            u.long("input_tokens") ?: u.long("prompt_tokens"),
            u.long("output_tokens") ?: u.long("completion_tokens"),
            u.long("total_tokens")
        )
}

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
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit = {}
    ): Turn = call(buildRequest(system, history, userText, previousResponseId), onDelta, onReasoning)

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

    private fun buildRequest(
        system: String,
        history: List<Msg>,
        userText: String,
        previousResponseId: String?
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
