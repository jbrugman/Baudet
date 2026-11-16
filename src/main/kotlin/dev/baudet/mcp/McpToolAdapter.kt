package dev.baudet.mcp

import dev.baudet.api.arr
import dev.baudet.api.str
import dev.baudet.tool.Tool
import dev.baudet.tool.ToolContext
import dev.baudet.tool.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One remote tool from one MCP server, dressed as a local [Tool] so the loop, the registry and the
 * model-facing protocol never learn the difference. The name is namespaced -- `mcp__<server>__<tool>`
 * -- so a remote tool cannot shadow `read_file` or collide with a second server's identically-named
 * tool; [sanitize] keeps each segment inside the `[A-Za-z0-9_]` charset a function-call name must stay
 * in for every backend.
 *
 * Nothing about a call runs without the human first: [execute] consults [ToolContext.mcpAllowServers]
 * for a standing grant, and otherwise [ToolContext.approveMcp], which asks on the tool's own card. It
 * keeps the default [canRunParallel] = false, so an MCP call never fans out or races a write -- an
 * external tool's side effects are exactly what that guard exists to fence.
 */
class McpToolAdapter(
    private val serverId: String,
    private val def: McpToolDef,
    private val client: McpClient
) : Tool {

    override val name: String = "mcp__${sanitize(serverId)}__${sanitize(def.name)}"
    override val description: String = def.description.ifBlank { "MCP tool `${def.name}` from `$serverId`" }
    override val parameters: JsonObject = def.inputSchema

    override fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val allowed = serverId in ctx.mcpAllowServers() ||
            ctx.approveMcp(serverId, def.name, args.toString())
        if (!allowed) {
            return ToolResult(
                ok = false,
                output = "the user declined to run MCP tool `${def.name}` from `$serverId`"
            )
        }
        return try {
            map(client.callTool(def.name, args))
        } catch (e: Exception) {
            ToolResult(ok = false, output = "MCP call to `${def.name}` on `$serverId` failed: ${e.message}")
        }
    }

    /**
     * Reduce an MCP `tools/call` result to a [ToolResult]. A tool reports success in `isError`; its
     * payload is an array of content parts, of which the `text` ones carry what the model should read.
     * Any other part kind (an image, a resource) is noted so the model knows the call produced something
     * it cannot show here, rather than reporting a silently empty success.
     */
    private fun map(result: JsonObject): ToolResult {
        val isError = (result["isError"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
        val parts = result.arr("content") ?: return ToolResult(
            ok = !isError,
            output = if (isError) "the MCP tool reported an error" else "(no output)"
        )
        val text = StringBuilder()
        var nonText = 0
        for (element in parts) {
            val part = element as? JsonObject ?: continue
            when (part.str("type")) {
                "text" -> part.str("text")?.let { if (text.isNotEmpty()) text.append('\n'); text.append(it) }
                else -> nonText++
            }
        }
        if (nonText > 0) {
            if (text.isNotEmpty()) text.append('\n')
            text.append("($nonText non-text part(s) not shown here)")
        }
        return ToolResult(
            ok = !isError,
            output = if (text.isEmpty()) (if (isError) "the MCP tool reported an error" else "(no output)")
            else text.toString()
        )
    }

    companion object {
        /** Collapse a name segment into `[A-Za-z0-9_]`, falling back to "tool" if nothing survives. */
        fun sanitize(raw: String): String =
            raw.map { if (it.isLetterOrDigit() || it == '_') it else '_' }
                .joinToString("").ifBlank { "tool" }
    }
}
