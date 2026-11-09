package dev.mule.tool

/**
 * Recognises a command as plainly read-only, so the user may opt in to running such a command
 * without a yes/no each time. This is a conservative gate on the approval prompt only: it never
 * widens what a command can touch (the folder boundary and the temp-folder fencing around the run
 * still apply), and it stays off until the user turns [dev.mule.config.AppConfig.autoApproveSafeCommands]
 * on. Anything that chains, redirects or substitutes is refused here, so a compound command always
 * falls back to an explicit approval.
 *
 * git is allowed as the read-only inspection this app is built around -- status, log, diff and
 * friends -- because a gate that cannot say "looking is fine" is a gate the user switches off.
 * It is admitted by subcommand, never by program name, and flags that write a file, run a pager or
 * point git elsewhere take the prompt back.
 */
object SafeCommands {

    /**
     * Shell syntax that could chain, redirect, substitute or group: none of it may be auto-run. An
     * argument quote is deliberately not among these, since a quoted read-only grep is exactly the
     * read the gate is meant to trust; the check still runs on the raw string, so a quoted command
     * that hides a semicolon, a pipe or a substitution is refused on that character all the same.
     */
    private val UNSAFE_CHARS = charArrayOf(
        ';', '&', '|', '<', '>', '$', '(', ')', '{', '}', '\\', '`',
        '*', '?', '!', '#', '~', '\n', '\r'
    )

    /** Single commands that only read; the name is taken from the program after any directory. */
    private val READ_ONLY = setOf(
        "ls", "pwd", "cat", "head", "tail", "wc", "grep", "rg", "find", "sort", "uniq",
        "echo", "date", "uname", "whoami", "df", "du", "file", "stat", "tree", "which"
    )

    /** find flags that hand control to another program or delete, so a find using them is not inert. */
    private val FIND_ESCAPE_FLAGS = setOf("-exec", "-execdir", "-ok", "-okdir", "-delete")

    /**
     * git subcommands that only read the repository. Anything else is refused: commit, push,
     * checkout, stash, clean and fetch all write, and a gate that runs without a human cannot
     * weigh that per invocation.
     */
    private val GIT_READ_ONLY_SUBCOMMANDS = setOf(
        "status", "log", "diff", "show", "blame", "shortlog", "ls-files", "ls-tree", "rev-parse", "describe"
    )

    /**
     * git flags that break the read-only promise: they write a file, run another program, or
     * point git at a different repository. Matched as a prefix because git accepts a value glued
     * to the flag, so --output=name and -c core.pager=rm are the same hazard as the bare flag.
     */
    private val GIT_ESCAPE_FLAGS = listOf(
        "--output", "--ext-diff", "--paginate", "-P", "-c", "--exec-path", "--git-dir", "--work-tree",
        "--namespace", "--super-prefix"
    )

    /** True only for a single simple command whose program is known read-only and carries no shell syntax. */
    fun isSafe(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.any { it in UNSAFE_CHARS }) return false
        val tokens = trimmed.split(Regex("\\s+"))
        val program = tokens.first().substringAfterLast('/')
        if (program !in READ_ONLY && program != "git") return false
        if (program == "find" && tokens.any { it in FIND_ESCAPE_FLAGS }) return false
        return program != "git" || gitIsReadOnly(tokens)
    }

    /** True when [tokens] is a git call that only inspects: a known read-only subcommand, no escaping flag. */
    private fun gitIsReadOnly(tokens: List<String>): Boolean {
        // A bare "git" prints usage; there is nothing to allow and nothing to fear, but it is not
        // an inspection either, so it keeps its prompt like any command this gate does not know.
        val subcommand = tokens.getOrNull(1) ?: return false
        if (subcommand !in GIT_READ_ONLY_SUBCOMMANDS) return false
        return tokens.none { flag -> GIT_ESCAPE_FLAGS.any { flag.startsWith(it) } }
    }
}
