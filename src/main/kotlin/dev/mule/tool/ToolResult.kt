package dev.mule.tool

/**
 * What a tool run hands back. [output] is model-facing and stays English (it is
 * fed straight to the model); [diff] is the human-facing unified preview shown
 * beside the approval prompt and only present when there is one; [undo] reverts a
 * write that already landed, so a bad edit is recoverable without git.
 */
data class ToolResult(
    val ok: Boolean,
    val output: String,
    val diff: String? = null,
    val undo: (() -> Unit)? = null
)
