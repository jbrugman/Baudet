package dev.mule.tool

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The always-allow list is only useful if one decision covers the variations a user means, and only
 * safe if it never covers more than they granted. These cases pin that line: a simple command signs
 * to a reusable signature, a chain signs to its own verbatim text so a short program pin can never
 * pull a chain through it, and a write flag signs away from a read flag.
 */
class CommandSignatureSpec : BehaviorSpec({

    given("a simple command") {
        `when`("reduced to a signature") {
            then("the program, its subcommand and its first flag survive, and quoting does not matter") {
                CommandSignature.of("mvn -B test") shouldBe "mvn -B"
                CommandSignature.of("git commit -m msg") shouldBe "git commit"
                CommandSignature.of("git status") shouldBe "git status"
                CommandSignature.of("grep pattern file") shouldBe "grep"
                CommandSignature.of("/usr/bin/ls -la") shouldBe "ls -la"
                CommandSignature.of("sed -n '1,2p' notes.txt") shouldBe "sed -n"
            }
        }
        `when`("pinned") {
            then("the stored value is that reusable signature") {
                CommandSignature.pinned("cat notes.txt") shouldBe "cat"
                CommandSignature.pinned("sed -n '1,2p' notes.txt") shouldBe "sed -n"
            }
        }
    }

    given("a write flag versus a read flag of the same program") {
        `when`("each is pinned") {
            then("they sign apart, so trusting a read never trusts a write") {
                CommandSignature.pinned("sed -n '1,2p' f") shouldNotBe CommandSignature.pinned("sed -i 's/a/b/' f")
                CommandSignature.pinned("sed -i 's/a/b/' f") shouldBe "sed -i"
            }
        }
    }

    given("a chained or redirected command") {
        `when`("classified") {
            then("it is not simple") {
                CommandSignature.isSimple("ls -la") shouldBe true
                CommandSignature.isSimple("pwd && ls") shouldBe false
                CommandSignature.isSimple("echo hi > out") shouldBe false
                CommandSignature.isSimple("echo \$(date)") shouldBe false
                CommandSignature.isSimple("cat f; rm f") shouldBe false
            }
        }
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
