package dev.mule.tool

import java.nio.file.Path

/**
 * What a tool needs to act: the project folder it is scoped to, whether writing
 * there is allowed, and how to ask the human before a write lands. [approve] is a
 * plain callback so the tool stays free of Swing -- the UI decides how to ask and
 * runs it on the event thread, while the tool blocks on the answer. A null [root]
 * or a false [writeGranted] is a reason to refuse, checked by the tool itself. The Read
 * and Execute rights default off and the two approvals default to deny, so callers that
 * never read, run or write need pass none of [readGranted], [execGranted], [approve] or
 * [approveCommand].
 */
class ToolContext(
    val root: Path?,
    val writeGranted: Boolean,
    val approve: (relativePath: String, diff: String) -> Boolean,
    val execGranted: Boolean = false,
    val approveCommand: (command: String, workingDir: String, timeoutSeconds: Int) -> Boolean =
        { _, _, _ -> false },
    val readGranted: Boolean = false
)
