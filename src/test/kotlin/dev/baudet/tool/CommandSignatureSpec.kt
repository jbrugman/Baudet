package dev.baudet.tool

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The always-allow list is only useful if one decision covers the variations a user means, and only
 * safe if it never covers more than they granted. The line these cases pin: a reusable signature is
 * reserved for a command the read-only gate already proves safe, so a pin can never be broader than an
 * operation that gate would run without a human. Every other command -- a payload tool, a chain -- pins
 * verbatim, so one read approval can never be reused to authorise a write.
 */
class CommandSignatureSpec : BehaviorSpec({

    given("a provably read-only command") {
        `when`("pinned") {
            then("a family signature survives, so one Always covers the same read over other files") {
                CommandSignature.pinned("cat notes.txt") shouldBe "cat"
                CommandSignature.pinned("cat other.txt") shouldBe "cat"
                CommandSignature.pinned("grep -n todo a.txt") shouldBe "grep -n"
                CommandSignature.pinned("grep -n todo b.txt") shouldBe "grep -n"
            }
            then("a git inspection pins program, subcommand and flags, dropping the read target") {
                CommandSignature.pinned("git status") shouldBe "git status"
                CommandSignature.pinned("git show HEAD") shouldBe "git show"
            }
            then("quoting does not change the sign") {
                CommandSignature.pinned("grep -n 'todo' notes.txt") shouldBe "grep -n"
            }
        }
    }

    given("a program that is not provably read-only") {
        `when`("pinned") {
            then("it signs to its whole trimmed text, so no family pin is ever granted to it") {
                CommandSignature.pinned("sed -n '1,2p' notes.txt") shouldBe "sed -n '1,2p' notes.txt"
                CommandSignature.pinned("mvn -B test") shouldBe "mvn -B test"
            }
            then("so approving one read of such a tool never covers a write by it") {
                CommandSignature.pinned("sed -n 1p f") shouldNotBe
                    CommandSignature.pinned("sed -n -i.bak s/a/b/ f")
                CommandSignature.pinned("sed -n '1,2p' f") shouldNotBe
                    CommandSignature.pinned("sed -i 's/a/b/' f")
            }
        }
    }

    given("a read-only program carrying one of its write or exec flags") {
        `when`("pinned") {
            then("it signs verbatim, because that flag means the gate no longer proves it a read") {
                CommandSignature.pinned("sort -o out.txt in.txt") shouldBe "sort -o out.txt in.txt"
                CommandSignature.pinned("sort -n in.txt") shouldNotBe
                    CommandSignature.pinned("sort -o out.txt in.txt")
            }
        }
    }

    given("a chained or redirected command") {
        `when`("pinned") {
            then("it stores its whole trimmed text, not the leading program word") {
                CommandSignature.pinned("  pwd && ls -la  ") shouldBe "pwd && ls -la"
                CommandSignature.pinned("cat f; rm f") shouldBe "cat f; rm f"
            }
            then("so a short pin of the first program cannot cover the whole chain") {
                CommandSignature.pinned("pwd && ls -la") shouldNotBe CommandSignature.pinned("pwd")
            }
        }
    }

    /*
     * A local model rewords the same command turn after turn -- an extra space here, a padded chain there.
     * A verbatim pin that misses on spacing is the nag this removes. Folding runs of spaces and tabs cannot
     * merge tokens or split one, so it forgets only horizontal spacing the shell ignores and never turns a
     * read into a write; a chain still folds to its own whole text, not to its leading program.
     */
    given("spellings that differ only in whitespace") {
        `when`("pinned") {
            then("they collapse to one text, so one Always keeps holding over reworded spacing") {
                CommandSignature.pinned("pwd  &&   ls -la") shouldBe "pwd && ls -la"
                CommandSignature.pinned("mvn   -B    test") shouldBe "mvn -B test"
            }
        }
    }

    /*
     * The trusted family is the second route an Always can take: a bare program, or git plus its subcommand,
     * whenever the gate clears that shape. A command it cannot clear is granted no family and keeps falling
     * back to its verbatim pin, so family trust never reaches past the single program the user named.
     */
    given("a command asked for its trusted family") {
        `when`("the program is safe to vouch for") {
            then("a family key is granted, so one Always covers the repeated build or commit") {
                CommandSignature.trustedEntry("mvn clean verify") shouldBe "mvn"
                CommandSignature.trustedEntry("git commit -m x") shouldBe "git commit"
            }
        }
        `when`("the program is not") {
            then("no family is granted, so it falls back to a verbatim pin") {
                (CommandSignature.trustedEntry("rm -rf x") == null) shouldBe true
                (CommandSignature.trustedEntry("bash -c x") == null) shouldBe true
            }
        }
    }

    /*
     * "Always allow" must mean always, the way a developer expects from a tool like Codex: one decision
     * on a pipeline silences every reworded variation of it. A chain of otherwise-safe segments therefore
     * earns a family, recorded as the chain's shape so a pipeline never matches a sequence. But the family
     * is only as safe as its weakest segment, so a single unsafe part strips the whole chain's family.
     */
    given("a chain of segments asked for its trusted family") {
        `when`("every segment is safe to vouch for") {
            then("the whole chain earns a family, so one Always covers every reworded variation") {
                CommandSignature.trustedEntry("git diff --stat | tail -30") shouldBe "git diff | tail"
                CommandSignature.trustedEntry("git diff HEAD | tail -50") shouldBe "git diff | tail"
                CommandSignature.trustedEntry("mvn -q compile && git status") shouldBe "mvn && git status"
            }
            then("the chain's operator is part of the key, so a pipeline never matches a sequence") {
                CommandSignature.trustedEntry("git diff | tail") shouldNotBe
                    CommandSignature.trustedEntry("git diff ; tail")
            }
        }
        `when`("one segment is blocklisted") {
            then("the whole chain is refused a family, so a trusted read cannot smuggle a destructive part") {
                (CommandSignature.trustedEntry("git diff | rm -rf x") == null) shouldBe true
                (CommandSignature.trustedEntry("echo hi && python evil.py") == null) shouldBe true
            }
        }
        `when`("one segment is a destructive git subcommand") {
            then("the whole chain is refused a family") {
                (CommandSignature.trustedEntry("git status && git reset --hard") == null) shouldBe true
            }
        }
        `when`("the chain carries a redirect or substitution") {
            then("no family is granted, so a redirect still falls back to a verbatim pin") {
                (CommandSignature.trustedEntry("git diff > out.txt") == null) shouldBe true
                (CommandSignature.trustedEntry("cat $(ls)") == null) shouldBe true
            }
        }
    }

    /*
     * A newline is a shell command separator, not spacing to forget: a second line is a second
     * command. Folding it away would let a read the gate vouches for drag a hidden write into a silent
     * Always, so a newline is left intact and the whole line keeps only its verbatim pin, never a family.
     */
    given("a command carrying a newline") {
        val nl = 10.toChar().toString()
        val tab = 9.toChar().toString()
        `when`("pinned") {
            then("the newline survives, so it is never mistaken for one folded harmless line") {
                CommandSignature.pinned("git status" + nl + "touch x").contains(nl) shouldBe true
            }
            then("a trusted read on the first line cannot vouch for a write riding the second") {
                CommandSignature.pinned("git status" + nl + "touch x") shouldNotBe
                    CommandSignature.pinned("git status")
            }
            then("horizontal spacing still folds, so a mere reword is not punished") {
                CommandSignature.pinned("mvn" + tab + "-B" + tab + "test") shouldBe "mvn -B test"
            }
        }
    }
})
