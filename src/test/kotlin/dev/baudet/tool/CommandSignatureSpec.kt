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
})
