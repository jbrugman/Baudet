package dev.mule.tool

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/**
 * The tools enabled for a turn. [definitions] renders the `tools` array the
 * Responses API expects; [get] dispatches a model request back to its handler.
 * Kept separate from any single tool so the loop sees one set regardless of how
 * many are wired, and an unknown name is a clean miss rather than a crash.
 */
class ToolRegistry(private val tools: List<Tool>) {

    private val byName: Map<String, Tool> = tools.associateBy { it.name }

    fun get(name: String): Tool? = byName[name]

    fun isEmpty(): Boolean = tools.isEmpty()

    /** The `tools` field for a Responses request: one function object per tool. */
    fun definitions(): String = buildJsonArray {
        tools.forEach { tool ->
            addJsonObject {
                put("type", "function")
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.parameters)
            }
        }
    }.toString()
}
