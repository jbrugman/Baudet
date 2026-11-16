package dev.baudet.mcp

import kotlinx.serialization.Serializable

/**
 * One MCP server the user opted into. An MCP server is arbitrary local code -- [command] is a program
 * Baudet launches -- so there is no default and nothing connects until the user adds a line here.
 * [command] plus [args] is the program and its argument vector launched over stdio; [env] is added to
 * the child environment for keys it needs. The id is what a tool name is namespaced under
 * (`mcp__<id>__<tool>`) and what an "always allow" grant records, so it must stay stable across edits.
 *
 * [timeoutSeconds] is how long the manager waits for one `tools/call` before it gives up and tells the
 * model, so a server that genuinely runs long (a local image model that can hold one call open for
 * minutes) needs a value above the default and gets it on its own line.
 */
@Serializable
data class McpServerConfig(
    val id: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    /**
     * The ceiling, in seconds, on one `tools/call` round trip to this server. It defaults to the protocol
     * ceiling [McpJsonRpc.CALL_TIMEOUT_SECONDS]; a slow server raises it per line, and a value of 0 or less
     * falls back to that ceiling rather than to no ceiling at all, so a mistyped line can never park a
     * turn forever waiting on a server that will not answer.
     */
    val timeoutSeconds: Long = McpJsonRpc.CALL_TIMEOUT_SECONDS
)
