package dev.baudet.tool

/**
 * One picture a tool returned, carried as raw base64 with its MIME type. It is kept out of
 * [ToolResult.output] on purpose: that string goes back to the model, and a megabyte of base64
 * there would crowd the context out without telling the model anything it can act on. The bytes
 * belong to the transcript row (see `ImageItem`), which is where a human actually looks at them.
 */
data class ToolImage(val mimeType: String, val base64: String)

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
    val cwd: String? = null,
    val images: List<ToolImage> = emptyList()
)
