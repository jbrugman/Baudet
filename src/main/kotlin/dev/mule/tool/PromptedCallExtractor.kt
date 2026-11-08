package dev.mule.tool

import dev.mule.api.Turn
import dev.mule.api.json
import dev.mule.api.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Reads tool calls from a reply on two channels. Native calls the backend already
 * returned (turn.functionCalls) come first and win; then the prompted reader scans for
 * fenced blocks tagged with the marker so a model that answers in text instead of a
 * native function_call still acts. A local model is given one strong marker rather than
 * being trusted to keep JSON out of prose, because those models emit stray braces freely.
 * Prose around fences is ignored and a block that will not parse is skipped, not thrown,
 * so one mangled call degrades to "no call" (the answer stands) instead of failing the
 * turn. Native argument strings that will not parse become an empty argument object, so
 * the tool reports the missing fields back to the model rather than crashing.
 */
class PromptedCallExtractor : CallExtractor {

    /** Three grave accents, built from a code point so the source never holds the run. */
    private val fenceChars = "\u0060\u0060\u0060"

    private val fence = Regex(fenceChars + CallExtractor.FENCE + "[ \\t]*\\n(.*?)" + fenceChars,
        RegexOption.DOT_MATCHES_ALL)

    override fun extract(turn: Turn): List<ToolCall> {
        val native = turn.functionCalls.mapIndexed { i, call ->
            ToolCall(call.name, parseArguments(call.arguments), call.callId ?: "native-$i")
        }
        val prompted = fence.findAll(turn.text).flatMap { parseBlock(it.groupValues[1]) }.toList()
        return native + prompted
    }

    private fun parseArguments(raw: String): JsonObject = try {
        json.parseToJsonElement(raw.trim()) as? JsonObject ?: JsonObject(emptyMap())
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }

    private fun parseBlock(body: String): List<ToolCall> = try {
        when (val element = json.parseToJsonElement(body.trim())) {
            is JsonArray -> element.mapNotNull { callFrom(it) }
            is JsonObject -> listOfNotNull(callFrom(element))
            else -> emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun callFrom(element: JsonElement): ToolCall? {
        val obj = element as? JsonObject ?: return null
        val name = obj.str("tool") ?: obj.str("name") ?: return null
        val args = (obj["arguments"] as? JsonObject) ?: (obj["args"] as? JsonObject) ?: JsonObject(emptyMap())
        return ToolCall(name, args)
    }
}
