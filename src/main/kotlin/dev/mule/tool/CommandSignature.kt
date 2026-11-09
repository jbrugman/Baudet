package dev.mule.tool

/**
 * Decides what a single Always decision pins, and what it therefore keeps silent about. Pinning the
 * whole command string forced the user to approve every little variation again, which made the
 * always-allow list useless in practice; pinning only the program word would trust more than the
 * user meant to grant. This splits the difference on the shape of the command.
 *
 * A simple command, one with no shell chaining, redirection, grouping or substitution, pins to its
 * signature: the program, its subcommand when the program is git, and its first flag otherwise. One
 * Always then covers the same command run with different arguments, and a flag that writes signs to
 * a different signature than a flag that only reads. A compound command pins to its whole trimmed
 * text instead, so the exact chain the user approved is trusted verbatim while a short signature pin
 * can never auto-run a chain that hides behind it.
 */
object CommandSignature {

    /** Shell syntax that chains, redirects, groups or substitutes. */
    private val COMPOUND_SYNTAX = charArrayOf(';', '&', '|', '<', '>', '$', '`', '(', ')', '{', '}', '\n', '\r')

    /** True when [command] carries none of that compound syntax and is therefore a single command. */
    fun isSimple(command: String): Boolean = command.none { it in COMPOUND_SYNTAX }

    /**
     * The signature of [command]: its program basename, plus its subcommand when the program is git,
     * plus its first flag otherwise. A bare command with no arguments signs to just the program.
     * Whitespace is the only separator, so quoting never changes the signature.
     */
    fun of(command: String): String {
        val tokens = command.trim().split(Regex("\\s+"))
        val program = tokens.first().substringAfterLast('/')
        val next = tokens.getOrNull(1)
        return when {
            program.isEmpty() -> program
            program == "git" && next != null -> "$program $next"
            next != null && next.startsWith("-") -> "$program $next"
            else -> program
        }
    }

    /**
     * The value to store for [command] in the always-allow list and to compare against when deciding
     * whether the same command still needs a prompt: its signature when simple, its whole trimmed
     * text when compound. Store and check must use this one function so the two sides never drift.
     */
    fun pinned(command: String): String = if (isSimple(command)) of(command) else command.trim()
}
