package dev.baudet.git

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The git tool hands the model a real git binary, so its whole safety rests on this pure
 * guard: a fixed read-only verb set, no mutating verb, and no dash-prefixed global flag that
 * could point git at another work tree. These are the exact rejections that keep a model
 * inside the repository it was given.
 */
class GitCommandSpec : BehaviorSpec({

    given("the guard for a read-only git tool") {
        `when`("a read-only verb is asked for") {
            then("status is allowed") { GitCommand.checkAllowed("status", emptyList()).shouldBeNull() }
            then("diff and log are allowed") {
                GitCommand.checkAllowed("diff", emptyList()).shouldBeNull()
                GitCommand.checkAllowed("log", emptyList()).shouldBeNull()
            }
        }
        `when`("a mutating verb is asked for") {
            then("commit is refused and steered to run_command") {
                val reason = GitCommand.checkAllowed("commit", emptyList())
                reason.shouldNotBeNull()
                reason shouldContain "run_command"
            }
            then("an unknown verb is refused") { GitCommand.checkAllowed("nope", emptyList()).shouldNotBeNull() }
        }
        `when`("a global git flag is smuggled in") {
            then("a leading-dash argument is refused") {
                val reason = GitCommand.checkAllowed("status", listOf("--git-dir", "/etc"))
                reason.shouldNotBeNull()
                reason shouldContain "not allowed"
            }
            then("even a short dash flag is refused") {
                GitCommand.checkAllowed("diff", listOf("-c", "x=y")).shouldNotBeNull()
            }
        }
        `when`("the arguments are harmless") {
            then("the end-of-options separator is allowed") {
                GitCommand.checkAllowed("diff", listOf("--", "weird-name")).shouldBeNull()
            }
            then("a plain path is allowed") {
                GitCommand.checkAllowed("blame", listOf("src/Main.kt")).shouldBeNull()
            }
        }
        `when`("no verb is given") {
            then("a blank command is refused") { GitCommand.checkAllowed("", emptyList()).shouldNotBeNull() }
        }
        `when`("branch is asked") {
            then("a bare branch is allowed, because it only lists") {
                GitCommand.checkAllowed("branch", emptyList()).shouldBeNull()
            }
            then("branch with a name is refused, because that would create a branch") {
                val reason = GitCommand.checkAllowed("branch", listOf("feature-x"))
                reason.shouldNotBeNull()
                reason shouldContain "lists only"
            }
        }
    }

    given("the guard when git writes are involved") {
        `when`("writes are disabled, the default") {
            then("a write verb is refused and steered to run_command") {
                val reason = GitCommand.checkAllowed("commit", emptyList())
                reason.shouldNotBeNull()
                reason shouldContain "run_command"
            }
            then("the advertised set hides every write verb") {
                GitCommand.advertised(false).contains("commit") shouldBe false
                GitCommand.advertised(true).contains("commit") shouldBe true
            }
        }
        `when`("add is asked with writes on") {
            then("a path is allowed") {
                GitCommand.checkAllowed("add", listOf("a.txt"), writesAllowed = true).shouldBeNull()
            }
            then("no path is refused, because git add with nothing does nothing useful") {
                GitCommand.checkAllowed("add", emptyList(), writesAllowed = true).shouldNotBeNull()
            }
            then("a dash flag such as -A is refused") {
                GitCommand.checkAllowed("add", listOf("-A"), writesAllowed = true).shouldNotBeNull()
            }
        }
        `when`("commit is asked with writes on") {
            then("a message makes it allowed") {
                GitCommand.checkAllowed("commit", emptyList(), writesAllowed = true, message = "note")
                    .shouldBeNull()
            }
            then("a blank or missing message is refused") {
                GitCommand.checkAllowed("commit", emptyList(), writesAllowed = true).shouldNotBeNull()
            }
            then("an inline --amend smuggled into args is refused") {
                GitCommand.checkAllowed("commit", listOf("--amend"), writesAllowed = true, message = "x")
                    .shouldNotBeNull()
            }
        }
        `when`("checkout is asked with writes on") {
            then("switching to one branch is allowed") {
                GitCommand.checkAllowed("checkout", listOf("main"), writesAllowed = true).shouldBeNull()
            }
            then("creating a branch with -b is allowed") {
                GitCommand.checkAllowed("checkout", listOf("-b", "new"), writesAllowed = true).shouldBeNull()
            }
            then("a path discard through -- is refused") {
                val r = GitCommand.checkAllowed("checkout", listOf("--", "a.txt"), writesAllowed = true)
                r.shouldNotBeNull()
                r shouldContain "discard"
            }
            then("more than one positional, which could detach HEAD, is refused") {
                GitCommand.checkAllowed("checkout", listOf("a", "b"), writesAllowed = true).shouldNotBeNull()
            }
        }
        `when`("stash is asked with writes on") {
            then("a non-destructive subcommand is allowed") {
                GitCommand.checkAllowed("stash", listOf("pop"), writesAllowed = true).shouldBeNull()
            }
            then("bare stash, which defaults to push, is allowed") {
                GitCommand.checkAllowed("stash", emptyList(), writesAllowed = true).shouldBeNull()
            }
            then("the destructive drop is refused") {
                val r = GitCommand.checkAllowed("stash", listOf("drop"), writesAllowed = true)
                r.shouldNotBeNull()
                r shouldContain "not allowed"
            }
        }
        `when`("push is asked with writes on") {
            then("a plain push is allowed") {
                GitCommand.checkAllowed("push", emptyList(), writesAllowed = true).shouldBeNull()
            }
            then("a remote and a branch are allowed") {
                GitCommand.checkAllowed("push", listOf("origin", "main"), writesAllowed = true).shouldBeNull()
            }
            then("a force flag is refused so a push can never be forced") {
                val r = GitCommand.checkAllowed("push", listOf("--force"), writesAllowed = true)
                r.shouldNotBeNull()
                r shouldContain "cannot force"
            }
        }
    }

    given("the argv builder for a cleared call") {
        `when`("status is requested against a root") {
            val argv = GitCommand.argv("/repo", "status", emptyList())
            then("it runs git at that root, without the pager") {
                argv.take(6) shouldContainExactly listOf(
                    "git", "-C", "/repo", "-c", "core.quotePath=false", "--no-pager"
                )
            }
            then("the verb carries its read-only default flags") {
                argv shouldContainExactly listOf(
                    "git", "-C", "/repo", "-c", "core.quotePath=false", "--no-pager",
                    "status", "--short", "--branch"
                )
            }
        }
        `when`("extra arguments are supplied") {
            then("they are appended after the verb flags, verbatim") {
                GitCommand.argv("/repo", "show", listOf("HEAD"))
                    .takeLast(2) shouldContainExactly listOf("show", "HEAD")
            }
        }
        `when`("a commit is built") {
            then("the message rides as its own -m value") {
                GitCommand.argv("/repo", "commit", emptyList(), message = "fix bug") shouldContainExactly listOf(
                    "git", "-C", "/repo", "-c", "core.quotePath=false", "--no-pager",
                    "commit", "-m", "fix bug"
                )
            }
        }
        `when`("an add is built against paths") {
            then("an end-of-options separator guards the paths from being read as flags") {
                GitCommand.argv("/repo", "add", listOf("a.txt")).takeLast(3) shouldContainExactly listOf(
                    "add", "--", "a.txt"
                )
            }
        }
    }
})
