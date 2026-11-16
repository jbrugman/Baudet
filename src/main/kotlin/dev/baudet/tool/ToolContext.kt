package dev.baudet.tool

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
     * Command pins the user chose to always allow: a command whose [CommandSignature.pinned] value is one
     * of these skips the approval prompt. Only a command [SafeCommands] proves read-only has a reusable
     * signature, so one Always covers the same read over different files; every other command pins to its
     * whole text, so a short pin can never auto-run a chain or a write.
     *
     * A provider rather than the set itself, because a tool loop runs many rounds on one context while
     * "Always allow" is clicked halfway through one of them. Handed over as a copy, a pin taken in round
     * two would not exist until the next turn, and the user would be asked about the very command they
     * just pinned.
     */
    val autoAllow: () -> Set<String> = { emptySet() },
    /** When true, a command [SafeCommands] recognises as read-only also skips the approval prompt. */
    val autoApproveSafe: Boolean = false,
    /**
     * Command families the user chose to trust: a command whose [CommandSignature.trustedEntry] value is
     * one of these skips the approval prompt. Only a single simple command that [SafeCommands.trustedKey]
     * clears has such a key -- a bare program, or git plus its subcommand, and never a chain, a shell, an
     * interpreter or a write flag -- so a trusted family cannot reach further than the program the user
     * named. Handed over as a provider like [autoAllow], so a family pinned mid-turn counts in that turn.
     */
    val trustedCommands: () -> Set<String> = { emptySet() },
    /**
     * Standing grants for external MCP servers: a server whose id is one of these runs its tools without
     * a per-call prompt. A provider like [autoAllow], so an "always" taken mid-turn counts in that turn.
     * Empty by default, so nothing external runs until the human grants it.
     */
    val mcpAllowServers: () -> Set<String> = { emptySet() },
    /**
     * Ask the human before an MCP tool runs. The callback runs the approval card itself and returns the
     * answer; it is the tool's responsibility to honour an "always" choice by adding the server to
     * [mcpAllowServers], exactly as [approveCommand] pins a command on AcceptForSession. It denies by
     * default, so a context that never wires MCP can never run an external tool.
     */
    val approveMcp: (serverId: String, tool: String, arguments: String) -> Boolean = { _, _, _ -> false }
)
