package dev.baudet.mcp

import kotlinx.serialization.Serializable

/**
 * One MCP server the user opted into. An MCP server is arbitrary local code -- [command] is a program
 * Baudet launches -- so there is no default and nothing connects until the user adds a line here.
 * [command] plus [args] is the program and its argument vector launched over stdio; [env] is added to
 * the child environment for keys it needs. The id is what a tool name is namespaced under
 * (`mcp__<id>__<tool>`) and what an "always allow" grant records, so it must stay stable across edits.
 */
@Serializable
data class McpServerConfig(
    val id: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true
)
