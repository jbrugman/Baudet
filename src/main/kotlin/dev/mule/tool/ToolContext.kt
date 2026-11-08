package dev.mule.tool

import java.nio.file.Path

/**
 * What a tool needs to act: the project folder it is scoped to, whether writing
 * there is allowed, and how to ask the human before a write lands. [approve] is a
 * plain callback so the tool stays free of Swing -- the UI decides how to ask and
 * runs it on the event thread, while the tool blocks on the answer. A null [root]
 * or a false [writeGranted] is a reason to refuse, checked by the tool itself.
 */
class ToolContext(
    val root: Path?,
    val writeGranted: Boolean,
    val approve: (relativePath: String, diff: String) -> Boolean
)
