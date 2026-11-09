package dev.mule.tool

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The safe-command gate lets the user opt out of approving harmless reads, so the cost of a wrong
 * "safe" is a command run without a human. These cases pin the conservative read: a single known
 * read-only command passes, and anything that chains, redirects, substitutes, deletes or names an
 * unknown program falls back to approval. git is judged per subcommand, so looking passes and
 * writing does not, even though both spell the same program.
 */
class SafeCommandsSpec : BehaviorSpec({

    given("single known read-only commands") {
        val safe = listOf(
            "ls", "pwd", "cat notes.txt", "echo hello world", "head -n 5 log.txt",
            "grep pattern file", "find . -name somefile", "du -sh", "wc -l file", "/bin/ls"
        )
        `when`("classified") {
            then("each is treated as safe") {
                safe.forEach { SafeCommands.isSafe(it) shouldBe true }
            }
        }
    }

    given("commands that chain, redirect, substitute or are not read-only") {
        val unsafe = listOf(
            "ls && rm -rf /", "echo hi > file", "cat f; rm f", "ls | grep x",
            "\$(whoami)", "cat f1 f2 || echo fail", "echo `date`", "find . -delete",
            "find . -exec rm {} ;", "sudo ls", "rm -rf /", "./deploy.sh", "mvn -B test"
        )
        `when`("classified") {
            then("none is treated as safe") {
                unsafe.forEach { SafeCommands.isSafe(it) shouldBe false }
            }
        }
    }

    given("git asked to only look") {
        val safe = listOf(
            "git status", "git log", "git diff", "git diff --stat", "git show HEAD",
            "git blame src/main/kotlin/App.kt", "git ls-files", "git rev-parse --short HEAD"
        )
        `when`("classified") {
            then("inspection runs without a prompt, which is what the opt-in promises") {
                safe.forEach { SafeCommands.isSafe(it) shouldBe true }
            }
        }
    }

    given("git asked to do more than look") {
        val unsafe = listOf(
            // Writes, network and history moves: the reason git is admitted by subcommand.
            "git", "git commit -m done", "git push", "git add .", "git checkout main",
            "git restore file", "git clean -fd", "git stash", "git fetch", "git config x y",
            "git branch -D stale", "git reset --hard",
            // Flags that keep a read-only subcommand from being read-only: a file written, a
            // program run, or a different repository addressed.
            "git log --output=/tmp/out", "git diff --ext-diff", "git log --paginate",
            "git -c core.pager=rm log", "git --git-dir /elsewhere status",
            "git --work-tree /elsewhere status"
        )
        `when`("classified") {
            then("none of it is treated as safe") {
                unsafe.forEach { SafeCommands.isSafe(it) shouldBe false }
            }
        }
    }

    given("a read-only command that quotes its argument") {
        // The gate once vetoed any quote, which nagged on ordinary quoted reads. A quote is now
        // allowed, but only over a known read-only program and never over a hidden metacharacter.
        val safe = listOf(
            "grep \"pattern\" file", "grep -n 'todo' notes.txt", "cat \"notes with space.txt\""
        )
        `when`("classified") {
            then("the quoted read passes, which is the nag this relaxation removes") {
                safe.forEach { SafeCommands.isSafe(it) shouldBe true }
            }
        }
        `when`("a quote hides a forbidden metacharacter") {
            then("it is still refused, because the check reads the raw string") {
                SafeCommands.isSafe("grep 'a; rm -rf /' f") shouldBe false
                SafeCommands.isSafe("grep \"a | b\" f") shouldBe false
                SafeCommands.isSafe("grep '$(whoami)' f") shouldBe false
            }
        }
    }

    given("an empty or blank command") {
        `when`("classified") {
            then("it is not safe") {
                SafeCommands.isSafe("") shouldBe false
                SafeCommands.isSafe("   ") shouldBe false
            }
        }
    }
})
