package dev.mule.tool

import kotlinx.serialization.json.JsonObject

/**
 * One capability the model can be offered. [description] and [parameters] are
 * model-facing and stay English; [parameters] is a JSON Schema object, the shape
 * the Responses API wants for a function tool. [execute] is pure of UI: any
 * approval is negotiated through [ToolContext], never by reaching into Swing.
 */
interface Tool {
    val name: String
    val description: String
    val parameters: JsonObject
    fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}
