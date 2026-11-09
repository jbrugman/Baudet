package dev.baudet.tool

/**
 * The single source of the read-only guarantee for command approval: it recognises a command as
 * plainly read-only, so the user may opt in to running such a command without a yes/no each time, and
 * [CommandSignature] grants a reusable Always signature to nothing this gate has not first approved.
 * This is a conservative gate on the approval prompt only: it never widens what a command can touch
 * (the folder boundary and the temp-folder fencing around the run still apply), and it stays off until
 * the user turns [dev.baudet.config.AppConfig.autoApproveSafeCommands] on. Anything that chains,
 * redirects or substitutes is refused here, so a compound command always falls back to an explicit
 * approval.
 *
 * git is allowed as the read-only inspection this app is built around -- status, log, diff and
 * friends -- because a gate that cannot say looking is fine is a gate the user switches off. It is
 * admitted by subcommand, never by program name, and flags that write a file, run a pager or point git
 * elsewhere take the prompt back.
 */
object SafeCommands {

    /**
     * Shell syntax that could chain, redirect, substitute or group: none of it may be auto-run. An
     * argument quote is deliberately not among these, since a quoted read-only grep is exactly the read
     * the gate is meant to trust; the check still runs on the raw string, so a quoted command that hides
     * a semicolon, a pipe or a substitution is refused on that character all the same.
     */
    private val UNSAFE_CHARS = charArrayOf(
        ';', '&', '|', '<', '>', '$', '(', ')', '{', '}', '\\', '`',
        '*', '?', '!', '#', '~', '\n', '\r'
    )

    /** Single commands that can read; each is then cleared or condemned by its flags in [DENY_FLAGS]. */
    private val READ_ONLY = setOf(
        "ls", "pwd", "cat", "head", "tail", "wc", "grep", "rg", "find", "sort", "uniq",
        "echo", "date", "uname", "whoami", "df", "du", "file", "stat", "tree", "which"
    )

    /**
     * Flags that hand a nominally read-only program write or exec power, matched per program. Without
     * this a name on [READ_ONLY] would be trusted whole, yet sort can write its output, ripgrep can run
     * a filter program, find can print to a file and date can set the clock: each is a write or an exec,
     * not a read, so its presence takes the prompt back. Matching treats a two-character flag as a short
     * option, caught even when bundled into a cluster, and a longer flag as a prefix, so a value glued
     * with --output=value is caught with --output. See [carriesWriteOrExecFlag].
     */
    private val DENY_FLAGS: Map<String, List<String>> = mapOf(
        "sort" to listOf("-o", "--output", "--compress-program"),
        "rg" to listOf("--pre", "--hostname-bin"),
        "find" to listOf(
            "-exec", "-execdir", "-ok", "-okdir", "-delete",
            "-fprint", "-fprint0", "-fls", "-fprintf"
        ),
        "date" to listOf("-s", "--set")
    )

    /**
     * git subcommands that only read the repository. Anything else is refused: commit, push, checkout,
     * stash, clean and fetch all write, and a gate that runs without a human cannot weigh that per
     * invocation.
     */
    private val GIT_READ_ONLY_SUBCOMMANDS = setOf(
        "status", "log", "diff", "show", "blame", "shortlog", "ls-files", "ls-tree", "rev-parse", "describe"
    )

    /**
     * git flags that break the read-only promise: they write a file, run another program, or point git
     * at a different repository. Matched as a prefix because git accepts a value glued to the flag, so
     * --output=name and -c core.pager=rm are the same hazard as the bare flag.
     */
    private val GIT_ESCAPE_FLAGS = listOf(
        "--output", "--ext-diff", "--paginate", "-P", "-c", "--exec-path", "--git-dir", "--work-tree",
        "--namespace", "--super-prefix"
    )

    /**
     * Programs that can never earn family trust, because a single one of their invocations can run
     * arbitrary code, destroy data, or escape the machine: shells and interpreters take a script as an
     * argument, and the rest delete, rewrite permissions, raise privilege, or fetch and run remote code.
     * Family trust is granted by [trustedKey]; a name here makes [trustedKey] return null, so even if a
     * bad string is typed into the trusted list it can never match a real run, and Always falls back to
     * the verbatim pin. This is the fence that keeps a broad Always from becoming an unattended shell.
     */
    private val BLOCKLIST = setOf(
        // shells and interpreters: one argument is arbitrary code
        "sh", "bash", "zsh", "dash", "ksh", "fish", "csh", "tcsh",
        "python", "python2", "python3", "pypy", "node", "deno", "bun", "ts-node",
        "ruby", "perl", "php", "lua", "tclsh", "expect", "osascript", "awk", "gawk", "sed",
        // direct destruction or permission change
        "rm", "rmdir", "mv", "cp", "dd", "mkfs", "shred", "truncate", "chmod", "chown", "chgrp",
        // privilege, process and system control
        "sudo", "su", "doas", "kill", "killall", "pkill", "reboot", "shutdown", "halt", "poweroff",
        "mount", "umount", "launchctl", "systemctl", "service",
        // network: fetch-and-run and remote control
        "curl", "wget", "nc", "ncat", "socat", "ssh", "scp", "sftp", "rsync"
    )

    /**
     * git subcommands that can throw away work or rewrite history, so they are never family-trusted:
     * one click on an unrelated git command must not let a later clean, reset or checkout discard the
     * working tree unattended. Every other subcommand, including the write subcommands a developer
     * repeats by intent (add, commit, push, branch), may be family-trusted once the user asks for it.
     */
    private val GIT_UNSAFE_TRUST_SUBCOMMANDS = setOf(
        "clean", "reset", "checkout", "restore", "switch", "rebase", "filter-branch"
    )

    /**
     * Relative build wrappers that may earn family trust by name. A project’s own ./mvnw and ./gradlew
     * launcher is the usual way its developer rebuilds, so a single Always on one should cover the next
     * build variant just as a bare mvn or gradle already does -- but only these two names, so a planted
     * or renamed executable cannot borrow a wrapper’s identity.
     */
    private val BUILD_WRAPPERS = setOf("mvnw", "gradlew")

    /**
     * True only for a single simple command whose program is a bare known read-only name, carries no
     * shell syntax, none of that program's write or exec flags, and no positional output. A program
     * given as a path is refused outright: only the bare name is trusted, so a planted binary cannot
     * borrow the identity of a safe command and be auto-run in its place.
     */
    fun isSafe(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.any { it in UNSAFE_CHARS }) return false
        val tokens = trimmed.split(Regex("\\s+"))
        val program = tokens.first()
        if (program.contains('/')) return false
        if (program == "git") return gitIsReadOnly(tokens)
        if (program !in READ_ONLY) return false
        val args = tokens.drop(1)
        if (args.any { carriesWriteOrExecFlag(program, it) }) return false
        if (writesToPositionalOutput(program, args)) return false
        return true
    }

    /**
     * The family key a single Always may grant trust to, or null when the command is shaped so that
     * family trust would be unsafe. Spaces and tabs are folded so the key stored at approval time and
     * the key checked at run time agree even if the command is spelled with different spacing, while a
     * newline is left as it is and refused: run through a shell it separates two commands, so a key must
     * never fold a second command into one harmless-looking line. The key
     * is the bare program, or git plus its subcommand, so one decision covers the repeated build or
     * commit that only differs in its message or flags -- which is exactly the nag a developer feels.
     *
     * A key is withheld for a shell metacharacter (a chain or redirect is never family-trusted), a
     * newline, a program [isFamilyTrustedProgram] will not vouch for, a name on [BLOCKLIST], a git
     * subcommand on [GIT_UNSAFE_TRUST_SUBCOMMANDS], or any
     * invocation that carries one of its write, exec or escape flags. That last rule is why trusting
     * sort, find or uniq cannot auto-write, and why trusting git cannot smuggle --output or -c: they
     * lose their key exactly when they stop being a read. The check mirrors [isSafe] but is not gated by
     * [READ_ONLY], because here the human, not the gate, is the one vouching for the program.
     */
    fun trustedKey(command: String): String? {
        val canonical = command.trim().replace(Regex("[ \\t]+"), " ")
        if (canonical.isEmpty()) return null
        if (canonical.any { it in UNSAFE_CHARS }) return null
        val tokens = canonical.split(Regex("\\s+"))
        val program = tokens.first()
        if (!isFamilyTrustedProgram(program)) return null
        if (program in BLOCKLIST) return null
        if (program == "git") {
            val subcommand = tokens.getOrNull(1) ?: return null
            if (subcommand in GIT_UNSAFE_TRUST_SUBCOMMANDS) return null
            val escapes = tokens.any { flag ->
                val bare = stripQuotes(flag)
                GIT_ESCAPE_FLAGS.any { bare.startsWith(it) }
            }
            return if (escapes) null else "git $subcommand"
        }
        val args = tokens.drop(1)
        if (args.any { carriesWriteOrExecFlag(program, it) }) return null
        if (writesToPositionalOutput(program, args)) return null
        return program
    }

    /**
     * Whether a program given to [trustedKey] may earn family trust, by name only. A bare name is judged
     * by the blocklist below. A relative single-segment wrapper is admitted only when its base name is a
     * known [BUILD_WRAPPERS] launcher; a directory path, a parent-directory hop, an absolute path or any
     * other wrapper is refused, so a planted or renamed binary cannot borrow a trusted wrapper’s identity.
     */
    private fun isFamilyTrustedProgram(program: String): Boolean {
        if (!program.contains('/')) return true
        if (!program.startsWith("./")) return false
        return program.removePrefix("./") in BUILD_WRAPPERS
    }

    /** True when [tokens] is a git call that only inspects: a known read-only subcommand, no escaping flag. */
    private fun gitIsReadOnly(tokens: List<String>): Boolean {
        // A bare "git" prints usage; there is nothing to allow and nothing to fear, but it is not
        // an inspection either, so it keeps its prompt like any command this gate does not know.
        val subcommand = tokens.getOrNull(1) ?: return false
        if (subcommand !in GIT_READ_ONLY_SUBCOMMANDS) return false
        // The shell strips quotes before git sees its arguments, so an escaping option wrapped in
        // quotes still reaches git bare. The check matches on the quote-stripped token: a quoted file
        // argument stays a read, while --output=... smuggled inside quotes is caught like the bare flag.
        return tokens.none { flag ->
            val bare = stripQuotes(flag)
            GIT_ESCAPE_FLAGS.any { bare.startsWith(it) }
        }
    }

    /**
     * Whether [token] hands [program] a write or exec flag. The shell runs the command after its own
     * parsing, so the raw token is first stripped of quotes: a deny flag wrapped in quotes still reaches
     * the program bare, and a check on the quoted text alone would let it slip through. A long flag is
     * matched by prefix, catching both --output file and --output=file. A short flag is matched both on
     * its own and bundled into a cluster, since sort -no sets its write-capable -o; a double-dash token
     * is never letter-scanned, so a benign long option is never caught on a shared letter.
     */
    private fun carriesWriteOrExecFlag(program: String, token: String): Boolean {
        val deny = DENY_FLAGS[program] ?: return false
        val bare = stripQuotes(token)
        if (!bare.startsWith("-")) return false
        return deny.any { flag ->
            if (flag.length == 2) {
                // A short option: its single letter anywhere in a single-dash cluster means it is set.
                !bare.startsWith("--") && bare.length > 1 && bare.substring(1).contains(flag[1])
            } else {
                bare.startsWith(flag)
            }
        }
    }

    /**
     * Whether [program] writes through a positional operand rather than a flag. uniq is the one name on
     * [READ_ONLY] that takes an output file as a second positional argument: with two operands it writes
     * the second, so it is read-only with at most one. The count is taken on the quote-stripped token,
     * so a quoted filename still counts as an operand. uniq flags that take a separate value can count
     * that value as an operand, which only ever asks for a prompt -- the safe direction for a gate whose
     * dangerous failure is a wrong safe verdict.
     */
    private fun writesToPositionalOutput(program: String, args: List<String>): Boolean {
        if (program != "uniq") return false
        return countPositionalOperands(args) >= 2
    }

    /**
     * How many operands [args] passes to the program, honouring the end-of-options marker. Before a
     * lone double dash a token starting with a dash is an option; from that dash onward every token is
     * an operand, even one that starts with a dash, because that is what the program then reads. A naive
     * count on a leading dash let uniq -- in.txt -out.txt look like one operand, when the shell hands
     * uniq two files and it writes the second. The count is taken on the quote-stripped token, so a
     * quoted filename still counts as an operand.
     */
    private fun countPositionalOperands(args: List<String>): Int {
        var operands = 0
        var optionsEnded = false
        for (token in args) {
            val bare = stripQuotes(token)
            when {
                optionsEnded -> operands++
                bare == "--" -> optionsEnded = true
                !bare.startsWith("-") -> operands++
            }
        }
        return operands
    }

    /** The token the program actually receives, with its shell quotes removed for the deny check. */
    private fun stripQuotes(token: String): String = token.filterNot { it == '\'' || it == '"' }
}
