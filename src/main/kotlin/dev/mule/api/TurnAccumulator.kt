package dev.mule.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

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
    private val calls = mutableListOf<FunctionCall>()
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
            incompleteReason = incompleteReason,
            functionCalls = calls.toList()
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

    /** Response-level bookkeeping: the id, the usage, why the run ended, and any calls. */
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

        // Native function calls ride two event shapes: the finished response carries
        // them in its output array, and some backends surface each item on its own done
        // event. Both are read; addCall dedupes so neither double-counts the other.
        (r["output"] as? JsonArray)?.forEach { collectCall(it) }
        if (type.endsWith("output_item.done")) collectCall(o.obj("item"))

        // Tolerant fallback: a backend may skip deltas and deliver the finished
        // text only. Adopt it just when nothing streamed, so nothing counts twice.
        if (type.endsWith("output_text.done") && out.isEmpty()) {
            o.str("text")?.let { if (it.isNotEmpty()) out.append(it) }
        }
        if (type.endsWith("reasoning_summary_text.done") && reason.isEmpty()) {
            o.str("text")?.let { if (it.isNotEmpty()) reason.append(it) }
        }
    }

    private fun collectCall(el: JsonElement?) {
        val item = el as? JsonObject ?: return
        if (item.str("type") != "function_call") return
        val name = item.str("name") ?: return
        val callId = item.str("call_id") ?: item.str("id")
        val args = item.str("arguments") ?: item.obj("arguments")?.toString() ?: ""
        addCall(FunctionCall(callId, name, args))
    }

    private fun addCall(c: FunctionCall) {
        val seen = calls.any {
            if (c.callId != null) it.callId == c.callId
            else it.name == c.name && it.arguments == c.arguments
        }
        if (!seen) calls.add(c)
    }

    private fun parseUsage(u: JsonObject): Usage =
        Usage(
            u.long("input_tokens") ?: u.long("prompt_tokens"),
            u.long("output_tokens") ?: u.long("completion_tokens"),
            u.long("total_tokens")
        )
}
