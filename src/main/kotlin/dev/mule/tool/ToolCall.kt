package dev.mule.tool

import kotlinx.serialization.json.JsonObject

/**
 * One tool the model asked for in a turn. [arguments] is the raw JSON object the
 * handler consumes, so nothing is guessed into place here: a missing or mistyped
 * field stays a tool problem the model sees and can correct, not a parse crash.
 * The shape is transport-agnostic on purpose -- prompted-JSON and a native
 * function_call both reduce to a name plus an argument object. [callId] carries the
 * backend's id for a native call, so its observation can be paired back to it.
 */
data class ToolCall(
    val tool: String,
    val arguments: JsonObject,
    val callId: String? = null
)
