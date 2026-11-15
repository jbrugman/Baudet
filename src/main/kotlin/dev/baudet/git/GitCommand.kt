package dev.baudet.git

/**
 * The guard and command builder behind the model-facing git tool. A coding assistant
 * needs to see a repository, but handing the model a raw git means handing it a program
 * that can rewrite history or point itself at another repository through global flags.
 * So this offers a fixed set of read-only subcommands, each mapped to safe default flags,
 * and refuses anything else: an unknown verb, or any argument that begins with a dash, since
 * those are exactly the global flags (a different work tree, a config override) that would
 * let a call escape the folder it is scoped to. The one dash token allowed is the literal --,
 * the standard end-of-options separator used to disambiguate a path.
 *
 * When writes are enabled a small set of verbs that change the repository -- add, commit,
 * checkout, stash and push -- is offered too, each fenced to the safe shape of its own
 * operation rather than run as raw git: a commit's message arrives through its own argument so
 * -a and --amend cannot slip through, a checkout only switches or creates a branch and can
 * never discard a path, a stash is limited to its non-destructive subcommands, and a push
 * cannot be forced. Global flags stay refused either way, so a call never leaves its
 * repository, and any write these rules cannot bound stays a job for run_command. Everything
 * is an argv list fed straight to a process, so no shell is ever involved. Pure of process and
 * Swing, so the rules are testable on their own.
 */
object GitCommand {

    /** The read-only model-facing verbs, in the order they are advertised. */
    private val READ_ONLY: List<String> = listOf(
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

    /** Verbs that change the repository, offered only when writes are enabled. */
    private val WRITE: List<String> = listOf("add", "commit", "checkout", "stash", "push")

    private val READ: Set<String> = READ_ONLY.toSet()
    private val WRITES: Set<String> = WRITE.toSet()

    /** The stash subcommands offered; the destructive ones (drop, clear) deliberately are not. */
    private val STASH_SUBS: Set<String> = setOf("push", "pop", "apply", "save", "list")

    /** True for a verb that changes the repository, so the tool knows it must ask before running. */
    fun isWrite(command: String): Boolean = command in WRITES

    /** The verbs the tool schema advertises, so the model only sees what it can call. */
    fun advertised(writesAllowed: Boolean = false): List<String> =
        if (writesAllowed) READ_ONLY + WRITE else READ_ONLY

    /**
     * Null when [command] with [args] is safe to run; otherwise a human-readable reason that
     * doubles as model-facing feedback, since the tool hands it straight back. Read-only verbs
     * are always checked; a write verb is checked against its own safe shape only when
     * [writesAllowed], and refused with an honest reason otherwise. [message] carries a commit's
     * message out of the argument list so no commit flag has to be parsed here.
     */
    fun checkAllowed(
        command: String,
        args: List<String>,
        writesAllowed: Boolean = false,
        message: String? = null
    ): String? {
        if (command.isBlank()) return "git: a command is required"
        if (command in READ) return checkReadOnly(command, args)
        if (command in WRITES) {
            if (!writesAllowed)
                return "git: '$command' changes the repository. Git writes are disabled; " +
                    "enable them in Settings, or use the run_command tool instead."
            return checkWrite(command, args, message)
        }
        return "git: '$command' is not an offered command. " +
            "Allowed: ${advertised(writesAllowed).joinToString()}."
    }

    /**
     * The argv to run [command] against the repository rooted at [root], with the pager off
     * (there is no terminal) and path quoting off (so non-ASCII names read back raw). Assumes
     * [checkAllowed] already cleared the call. A commit's [message] rides as its own -m value.
     */
    fun argv(root: String, command: String, args: List<String>, message: String? = null): List<String> =
        listOf("git", "-C", root, "-c", "core.quotePath=false", "--no-pager") + base(command, message) + args

    /**
     * A readable one-line form of the run, shown on the approval card a write raises for the human
     * to answer and used as the pin key a repeat of the same write is matched against. The routing
     * noise (the -C root and the two -c/--no-pager options) is dropped so the card reads as a git
     * command a person would type; a token with a space is quoted so the message stays legible.
     */
    fun summary(command: String, args: List<String>, message: String?): String {
        val tail = base(command, message) + args
        return "git " + tail.joinToString(" ") { if (it.contains(" ")) "\"" + it + "\"" else it }
    }

    /** The read-only verb rules: no dash option that could escape the folder, and branch lists only. */
    private fun checkReadOnly(command: String, args: List<String>): String? {
        args.firstOrNull { it.startsWith("-") && it != "--" }?.let { flag ->
            return "git: the argument '$flag' is not allowed; git global options cannot be passed."
        }
        // branch is offered to list the branches, which is what bare branch does. Any leftover
        // argument is a name (dash options were refused above), and git branch <name> would create a
        // branch -- a write this read-only path must never perform.
        if (command == "branch" && args.any { it != "--" })
            return "git: branch lists only here; it cannot create, rename or delete a branch."
        return null
    }

    /** The safe shape of each offered write verb. Anything broader is refused rather than run raw. */
    private fun checkWrite(command: String, args: List<String>, message: String?): String? {
        val flags = args.filter { it.startsWith("-") && it != "--" }
        val positional = args.filter { !it.startsWith("-") }
        return when (command) {
            // add only stages the paths named; a dash here would be patch mode or -A, so paths only.
            "add" -> when {
                flags.isNotEmpty() -> flagRefusal(flags.first())
                positional.isEmpty() -> "git: add needs at least one path to stage."
                else -> null
            }
            // The message is its own argument; a flag in args would be -a or --amend, never allowed.
            "commit" -> when {
                message.isNullOrBlank() -> "git: commit needs a message in the 'message' argument."
                flags.isNotEmpty() -> flagRefusal(flags.first())
                else -> null
            }
            "checkout" -> checkCheckout(args, flags, positional)
            // Only the non-destructive subcommands; drop and clear are deliberately absent.
            "stash" -> when {
                flags.isNotEmpty() -> flagRefusal(flags.first())
                positional.size > 1 -> "git: stash takes a single subcommand."
                positional.isNotEmpty() && positional.first() !in STASH_SUBS ->
                    "git: stash '${positional.first()}' is not allowed; only ${STASH_SUBS.joinToString()} are."
                else -> null
            }
            // No flags at all, so a force push is impossible; at most a remote and a branch follow.
            "push" -> when {
                flags.isNotEmpty() ->
                    "git: push takes no flags here, so it cannot force; pass only an optional remote and branch."
                positional.size > 2 -> "git: push takes at most a remote and a branch."
                else -> null
            }
            else -> "git: '$command' is not an offered write."
        }
    }

    /**
     * checkout switches to one existing branch, or creates one with -b/-B. -- is refused because
     * `git checkout -- <path>` throws away work, which is not a branch operation; more than one
     * positional could name a path or detach HEAD, so exactly one is allowed either way.
     */
    private fun checkCheckout(args: List<String>, flags: List<String>, positional: List<String>): String? {
        if (args.contains("--"))
            return "git: checkout switches or creates a branch here; it cannot check out paths or discard changes."
        return when (flags.size) {
            0 -> if (positional.size == 1) null
            else "git: checkout needs exactly one branch to switch to."
            1 -> when {
                flags.first() != "-b" && flags.first() != "-B" -> flagRefusal(flags.first())
                positional.size == 1 -> null
                else -> "git: ${flags.first()} needs exactly one new branch name."
            }
            else -> "git: checkout takes at most one -b/-B flag."
        }
    }

    private fun flagRefusal(flag: String): String =
        "git: the argument '$flag' is not allowed for this command."

    /** The subcommand plus its safe default flags, or an empty list if unknown. */
    private fun base(command: String, message: String?): List<String> = when (command) {
        "status" -> listOf("status", "--short", "--branch")
        "diff" -> listOf("diff")
        "diff_staged" -> listOf("diff", "--cached")
        "log" -> listOf("log", "--oneline", "-25")
        "show" -> listOf("show")
        "branch" -> listOf("branch")
        "ls_files" -> listOf("ls-files")
        "rev_parse" -> listOf("rev-parse")
        "blame" -> listOf("blame")
        "add" -> listOf("add", "--")
        "commit" -> listOf("commit", "-m", message.orEmpty())
        "checkout" -> listOf("checkout")
        "stash" -> listOf("stash")
        "push" -> listOf("push")
        else -> emptyList()
    }
}
