package dev.baudet.tool

/**
 * One pass of the loop: what the assistant said, the calls it asked for, and the
 * result each call returned. [results] aligns with [calls] in order and size when
 * there were any; an empty [calls] marks the final answer. The loop keeps every
 * step so the UI can show what ran and a test can assert on the whole trace, not
 * just the end text.
 */
data class ToolStep(
    val assistantText: String,
    val calls: List<ToolCall>,
    val results: List<ToolResult>
)
