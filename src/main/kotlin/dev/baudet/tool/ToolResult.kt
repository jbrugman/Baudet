package dev.baudet.tool

/**
 * What a tool run hands back. [output] is model-facing and stays English (it is
 * fed straight to the model); [diff] is the human-facing unified preview shown
 * beside the approval prompt and only present when there is one; [undo] reverts a
 * write that already landed, so a bad edit is recoverable without git.
 *
 * The process fields are for the human, not the model: a run that already spells "exit code:
 * 2" inside [output] would only repeat itself in a card header, and a header assembled from
 * prose is a header that guesses. [exitCode] stays null exactly when no code was produced --
 * a timeout, or a command that never started -- which is the distinction the app exists to
 * keep. Defaults keep every tool that has no process to report unchanged.
 */
data class ToolResult(
    val ok: Boolean,
    val output: String,
    val diff: String? = null,
    val undo: (() -> Unit)? = null,
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    val durationMs: Long = 0L,
    val cwd: String? = null
)
