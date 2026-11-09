package dev.mule.tool

/**
 * Recognises a command as plainly read-only, so the user may opt in to running such a command
 * without a yes/no each time. This is a conservative gate on the approval prompt only: it never
 * widens what a command can touch (the folder boundary and the temp-folder fencing around the run
 * still apply), and it stays off until the user turns [dev.mule.config.AppConfig.autoApproveSafeCommands]
 * on. Anything that chains, redirects or substitutes is refused here, so a compound command always
 * falls back to an explicit approval.
 */
object SafeCommands {

    /** Shell syntax that could chain, redirect, substitute or group: none of it may be auto-run. */
    private val UNSAFE_CHARS = charArrayOf(
        ';', '&', '|', '<', '>', '$', '(', ')', '{', '}', '\\', '`',
        '\'', '"', '*', '?', '!', '#', '~', '\n', '\r'
    )

    /** Single commands that only read; the name is taken from the program after any directory. */
    private val READ_ONLY = setOf(
        "ls", "pwd", "cat", "head", "tail", "wc", "grep", "rg", "find", "sort", "uniq",
        "echo", "date", "uname", "whoami", "df", "du", "file", "stat", "tree", "which"
    )

    /** find flags that hand control to another program or delete, so a find using them is not inert. */
    private val FIND_ESCAPE_FLAGS = setOf("-exec", "-execdir", "-ok", "-okdir", "-delete")

    /** True only for a single simple command whose program is known read-only and carries no shell syntax. */
    fun isSafe(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.any { it in UNSAFE_CHARS }) return false
        val tokens = trimmed.split(Regex("\\s+"))
        val program = tokens.first().substringAfterLast('/')
        if (program !in READ_ONLY) return false
        if (program == "find" && tokens.any { it in FIND_ESCAPE_FLAGS }) return false
        return true
    }
}
