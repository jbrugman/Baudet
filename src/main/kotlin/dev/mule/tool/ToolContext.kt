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
    val readGranted: Boolean = false,
    /**
     * Command signatures the user chose to always allow: a command whose signature matches one of
     * these skips the approval prompt. The match is by signature rather than whole string, so one
     * Always decision covers the same command run with different arguments. ExecuteTool applies
     * this only to a simple command, so a signature can never auto-run a chained or substituted one.
     *
     * A provider rather than the set itself, because a tool loop runs many rounds on one context
     * while "Always allow" is clicked halfway through one of them. Handed over as a copy, a pin
     * taken in round two would not exist until the next turn, and the user would be asked about
     * the very command they just pinned.
     */
    val autoAllow: () -> Set<String> = { emptySet() },
    /** When true, a command [SafeCommands] recognises as read-only also skips the approval prompt. */
    val autoApproveSafe: Boolean = false
)
