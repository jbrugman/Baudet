package dev.mule.git

/**
 * The guard and command builder behind the model-facing git tool. A coding assistant
 * needs to see a repository, but handing the model a raw git means handing it a program
 * that can rewrite history or point itself at another repository through global flags.
 * So this offers a fixed set of read-only subcommands, each mapped to safe default flags,
 * and refuses anything else: a mutating verb, an unknown verb, or any argument that begins
 * with a dash, since those are exactly the global flags (a different work tree, a config
 * override) that would let a call escape the folder it is scoped to. The one dash token
 * allowed is the literal --, the standard end-of-options separator used to disambiguate a
 * path. Everything is a argv list fed straight to a process, so no shell is ever involved.
 * Pure of process and Swing, so the rules are testable on their own.
 */
object GitCommand {

    /** The read-only model-facing verbs, in the order they are advertised. */
    private val COMMANDS: List<String> = listOf(
        "status",
        "diff",
        "diff_staged",
        "log",
        "show",
        "branch",
        "ls_files",
        "rev_parse",
        "blame"
    )

    /** Membership view over [COMMANDS], kept as a set for O(1) checks in [checkAllowed]. */
    private val ALLOWED: Set<String> = COMMANDS.toSet()

    /** The verbs the tool schema advertises, so the model only sees what it can call. */
    val advertised: List<String> get() = COMMANDS

    /**
     * Null when [command] with [args] is safe to run; otherwise a human-readable reason
     * that doubles as model-facing feedback, since the tool hands it straight back.
     */
    fun checkAllowed(command: String, args: List<String>): String? {
        if (command.isBlank()) return "git: a command is required"
        if (command !in ALLOWED)
            return "git: '$command' is not a read-only command. " +
                "Allowed: ${COMMANDS.joinToString()}. " +
                "For anything that changes the repository, use the run_command tool instead."
        args.firstOrNull { it.startsWith("-") && it != "--" }?.let { flag ->
            return "git: the argument '$flag' is not allowed; git global options cannot be passed."
        }
        return null
    }

    /**
     * The argv to run [command] against the repository rooted at [root], with the pager
     * off (there is no terminal) and path quoting off (so non-ASCII names read back raw).
     * Assumes [checkAllowed] already cleared the call.
     */
    fun argv(root: String, command: String, args: List<String>): List<String> =
        listOf("git", "-C", root, "-c", "core.quotePath=false", "--no-pager") + base(command) + args

    /** The subcommand plus its read-only default flags, or an empty list if unknown. */
    private fun base(command: String): List<String> = when (command) {
        "status" -> listOf("status", "--short", "--branch")
        "diff" -> listOf("diff")
        "diff_staged" -> listOf("diff", "--cached")
        "log" -> listOf("log", "--oneline", "-25")
        "show" -> listOf("show")
        "branch" -> listOf("branch")
        "ls_files" -> listOf("ls-files")
        "rev_parse" -> listOf("rev-parse")
        "blame" -> listOf("blame")
        else -> emptyList()
    }
}
