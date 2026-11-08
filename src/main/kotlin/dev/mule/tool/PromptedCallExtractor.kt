package dev.mule.tool

import dev.mule.api.Turn
import dev.mule.api.json
import dev.mule.api.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The prompted-JSON reader: it scans a reply for ```$FENCE``` fences and reads the
 * calls inside. A local model is given one strong, unmistakable marker rather than
 * being trusted to keep JSON out of prose, because those models emit stray braces
 * freely. Prose around the fences is ignored; a block that will not parse is skipped
 * rather than thrown, so one mangled call degrades to "no call" (the answer stands)
 * instead of failing the turn. Multiple fences and an array body are both accepted.
 */
class PromptedCallExtractor : CallExtractor {

    private val fence = Regex("```" + CallExtractor.FENCE + "[ \\t]*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

    override fun extract(turn: Turn): List<ToolCall> =
        fence.findAll(turn.text).flatMap { match -> parseBlock(match.groupValues[1]) }.toList()

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
