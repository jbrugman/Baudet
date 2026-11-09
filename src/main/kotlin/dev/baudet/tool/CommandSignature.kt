package dev.baudet.tool

/**
 * Decides what a single Always decision pins, and what it therefore keeps silent about.
 *
 * The read-only promise lives entirely in [SafeCommands]; this object grants no trust of its own. A
 * reusable family signature is given only to a command that promise already holds, so a stored pin can
 * never be broader than an operation the gate would itself run without a human. Every other command -- a
 * chain, a redirect, or a program the gate cannot prove read-only -- pins to its whole trimmed text, so
 * the trust granted is exactly the command the user approved.
 *
 * That is what closes the gap where approving one read of a tool used to cover a write by the same tool:
 * a program such as sed keeps its write power in its positional script, so it is never provably read-only,
 * and both its read and its write now sign to their own verbatim text. One decision no longer reaches the
 * other. The cost is deliberate: a payload tool must be re-approved for each variation.
 */
object CommandSignature {

    /**
     * The value to store for [command] in the always-allow list and to compare against when deciding
     * whether the same command still needs a prompt: a family signature when [SafeCommands] proves it
     * read-only, its whole trimmed text otherwise. Store and check must use this one function so the two
     * sides never drift.
     */
    fun pinned(command: String): String {
        // Fold runs of spaces and tabs only, so a command that differs from another merely by padding pins
        // and matches as one text: a chain trusted verbatim should not nag again because the model padded
        // its spacing. A newline is deliberately left intact. Run through a shell it separates two commands,
        // so one that survives the trim marks a genuine second command; it must keep that meaning and be
        // refused by the read-only gate below, never folded into one harmless-looking line that a family
        // or signature would then vouch for.
        val canonical = command.trim().replace(Regex("[ \\t]+"), " ")
        return if (SafeCommands.isSafe(canonical)) signature(canonical) else canonical
    }

    /**
     * The trusted family key for [command], if it has one: the bare program, or git plus its subcommand,
     * only when [SafeCommands.trustedKey] judges that family safe to vouch for. A non-null key is what a
     * single Always stores in the trusted list and what a later run is matched against, so store and check
     * share one definition and never drift. Null means the command has no safe family: it keeps its
     * verbatim pin from [pinned], so a shell, a payload tool or a chain is trusted exactly as approved.
     */
    fun trustedEntry(command: String): String? = SafeCommands.trustedKey(command)

    /**
     * The reusable signature of a command the gate already proved read-only: its program, its subcommand
     * when the program is git, and its flags sorted, so quoting and argument order never change the sign.
     * A provably read-only program carries no positional script that can write, so one pin then safely
     * covers the same read repeated over different files; positional file arguments are dropped for that
     * reason.
     */
    private fun signature(command: String): String {
        val tokens = command.split(Regex("\\s+"))
        val program = tokens.first()
        return if (program == "git") {
            val sub = tokens.getOrNull(1)
            val flags = tokens.drop(2).filter { it.startsWith("-") }.distinct().sorted()
            (listOfNotNull(program, sub) + flags).joinToString(" ")
        } else {
            val flags = tokens.drop(1).filter { it.startsWith("-") }.distinct().sorted()
            (listOf(program) + flags).joinToString(" ")
        }
    }
}
