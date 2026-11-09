package dev.baudet.git

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
    }
})
