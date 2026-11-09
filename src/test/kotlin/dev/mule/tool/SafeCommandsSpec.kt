package dev.mule.tool

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The safe-command gate lets the user opt out of approving harmless reads, so the cost of a wrong
 * "safe" is a command run without a human. These cases pin the conservative read: a single known
 * read-only command passes, and anything that chains, redirects, substitutes, deletes or names an
 * unknown program falls back to approval.
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
            "find . -exec rm {} ;", "sudo ls", "rm -rf /", "./deploy.sh", "mvn -B test",
            "git status"
        )
        `when`("classified") {
            then("none is treated as safe") {
                unsafe.forEach { SafeCommands.isSafe(it) shouldBe false }
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
