package dev.mule.tool

/**
 * Dispatches one [ToolCall] to its handler. Two things that could throw -- an
 * unknown name and a handler that throws -- are turned into a model-facing
 * [ToolResult] instead, because the model should see a failed tool as feedback it
 * can act on, not a crashed loop. A known tool's own result is passed through
 * untouched, including its [ToolResult.undo] handle.
 */
class ToolRunner(private val registry: ToolRegistry) {

    fun run(call: ToolCall, ctx: ToolContext): ToolResult {
        val tool = registry.get(call.tool)
            ?: return ToolResult(ok = false, output = "no tool named `${call.tool}` is available")
        return try {
            tool.execute(call.arguments, ctx)
        } catch (e: Exception) {
            ToolResult(ok = false, output = "tool `${call.tool}` failed: ${e.message}")
        }
    }
}
