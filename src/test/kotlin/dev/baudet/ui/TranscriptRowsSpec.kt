package dev.baudet.ui

import dev.baudet.i18n.Language
import dev.baudet.i18n.Texts
import dev.baudet.thread.ApprovalDecision
import dev.baudet.thread.CommandExecutionItem
import dev.baudet.thread.DiffStats
import dev.baudet.thread.FileChangeItem
import dev.baudet.thread.ItemStatus
import dev.baudet.thread.PlanEntryStatus
import dev.baudet.thread.ToolCallItem
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.Locale

/**
 * The words a card shows are the words a reader judges the machine by, so they are tested rather
 * than eyeballed. Two of the rules below are rules about honesty, not about layout: a run that
 * never produced an exit code has to be named as missing one instead of being rounded to the exit
 * code a reader would expect, and a patch header has to stay a header so that two file paths are
 * never counted as a deletion.
 */
class TranscriptRowsSpec : BehaviorSpec({

    beforeEach { Texts.language = Language.EN }
    afterEach { Texts.language = Language.EN }

    given("a command that ended with an exit code") {
        `when`("it succeeded") {
            then("the verdict names the code") {
                RowText.outcome(
                    CommandExecutionItem(
                        itemId = "i",
                        command = "true",
                        status = ItemStatus.Completed,
                        exitCode = 0
                    )
                ) shouldBe "exit 0"
            }
        }
        `when`("it failed") {
            then("the verdict names the code that says so") {
                RowText.outcome(
                    CommandExecutionItem(
                        itemId = "i",
                        command = "false",
                        status = ItemStatus.Failed,
                        exitCode = 1
                    )
                ) shouldBe "exit 1"
            }
        }
        `when`("it took measurable time") {
            then("the verdict carries the duration") {
                RowText.outcome(
                    CommandExecutionItem(
                        itemId = "i",
                        command = "ls",
                        status = ItemStatus.Completed,
                        exitCode = 0,
                        durationMs = 1_234L
                    )
                ) shouldBe "exit 0 \u00b7 1.2 s"
            }
        }
    }

    given("a command that never produced an exit code") {
        `when`("the run finished without one") {
            then("it is named as missing rather than rounded to exit 0") {
                RowText.outcome(
                    CommandExecutionItem(
                        itemId = "i",
                        command = "mystery",
                        status = ItemStatus.Completed,
                        exitCode = null
                    )
                ) shouldBe "no exit code"
            }
        }
        `when`("the verdict is missing") {
            then("the card says it is still running rather than showing nothing") {
                RowText.outcome(
                    CommandExecutionItem(itemId = "i", command = "sleep 10")
                ) shouldBe "running\u2026"
            }
        }
        `when`("the user stopped it") {
            then("the verdict says it was stopped") {
                RowText.outcome(
                    CommandExecutionItem(
                        itemId = "i",
                        command = "sleep 10",
                        status = ItemStatus.Interrupted
                    )
                ) shouldBe "stopped"
            }
        }
    }

    given("a command that timed out") {
        `when`("the timeout is known") {
            then("the verdict says what it ran into") {
                RowText.outcome(
                    CommandExecutionItem(
                        itemId = "i",
                        command = "sleep 999",
                        status = ItemStatus.Failed,
                        timeoutSeconds = 30,
                        timedOut = true
                    )
                ) shouldBe "timed out after 30 s"
            }
        }
        `when`("a code arrived as well") {
            then("the timeout wins, because the run was cut short whatever it left behind") {
                RowText.outcome(
                    CommandExecutionItem(
                        itemId = "i",
                        command = "sleep 999",
                        status = ItemStatus.Failed,
                        timeoutSeconds = 5,
                        exitCode = 143,
                        timedOut = true
                    )
                ) shouldBe "timed out after 5 s"
            }
        }
        `when`("no timeout was recorded") {
            then("the verdict still refuses to call it an exit code") {
                RowText.outcome(
                    CommandExecutionItem(
                        itemId = "i",
                        command = "sleep 999",
                        status = ItemStatus.Failed,
                        timedOut = true
                    )
                ) shouldBe "timed out after 0 s"
            }
        }
    }

    given("a run that is still open") {
        `when`("a code arrives while the item is still in progress") {
            then("the card keeps saying running, because a verdict belongs to a finished run") {
                RowText.outcome(
                    CommandExecutionItem(itemId = "i", command = "make", exitCode = 0, timedOut = false)
                ) shouldBe "running\u2026"
            }
        }
    }

    given("a duration") {
        `when`("it is under a second") {
            then("it is counted in milliseconds") {
                RowText.duration(500L) shouldBe "500 ms"
            }
        }
        `when`("it is under a minute") {
            then("it is spelled with one tenth of a second") {
                RowText.duration(1_234L) shouldBe "1.2 s"
            }
        }
        `when`("it passes a minute") {
            then("the minute and the remainder are both named") {
                RowText.duration(65_000L) shouldBe "1 m 5 s"
            }
        }
        `when`("it is negative, because a clock went backwards") {
            then("it reads as nothing rather than as a minus") {
                RowText.duration(-9L) shouldBe "0 ms"
            }
        }
        `when`("the JVM runs in a locale that writes its decimals with a comma") {
            then("the card keeps the decimal point, so one run reads the same everywhere") {
                val previous = Locale.getDefault()
                try {
                    Locale.setDefault(Locale.GERMANY)
                    RowText.duration(1_234L) shouldBe "1.2 s"
                } finally {
                    Locale.setDefault(previous)
                }
            }
        }
    }

    given("a command verdict") {
        `when`("the code is 0") {
            then("the card stays neutral") {
                RowText.commandFailed(
                    CommandExecutionItem(
                        itemId = "i",
                        command = "true",
                        status = ItemStatus.Completed,
                        exitCode = 0
                    )
                ) shouldBe false
            }
        }
        `when`("the code is nonzero") {
            then("the card colours it") {
                RowText.commandFailed(
                    CommandExecutionItem(
                        itemId = "i",
                        command = "false",
                        status = ItemStatus.Failed,
                        exitCode = 2
                    )
                ) shouldBe true
            }
        }
        `when`("a missing code is not a failure") {
            then("the verdict alone carries that, and the colour does not guess") {
                RowText.commandFailed(
                    CommandExecutionItem(itemId = "i", command = "mystery", status = ItemStatus.Completed)
                ) shouldBe false
            }
        }
        `when`("it timed out or was stopped") {
            then("both count as failure") {
                RowText.commandFailed(
                    CommandExecutionItem(
                        itemId = "i",
                        command = "sleep 999",
                        status = ItemStatus.Failed,
                        timedOut = true
                    )
                ) shouldBe true
                RowText.commandFailed(
                    CommandExecutionItem(
                        itemId = "i",
                        command = "sleep 10",
                        status = ItemStatus.Interrupted
                    )
                ) shouldBe true
            }
        }
    }

    given("a write card") {
        `when`("it landed") {
            then("the head carries the path and the counts") {
                RowText.change(
                    FileChangeItem(
                        itemId = "i",
                        path = "src/App.kt",
                        status = ItemStatus.Completed,
                        additions = 3,
                        deletions = 1
                    )
                ) shouldBe "src/App.kt  +3 \u22121"
            }
        }
        `when`("the user refused it") {
            then("the head says rejected instead of counting a write that never happened") {
                RowText.change(
                    FileChangeItem(itemId = "i", path = "src/App.kt", status = ItemStatus.Failed)
                ) shouldBe "src/App.kt  rejected"
            }
        }
        `when`("it has not resolved yet") {
            then("the head says so") {
                RowText.change(
                    FileChangeItem(itemId = "i", path = "src/App.kt")
                ) shouldBe "src/App.kt  running\u2026"
            }
        }
    }

    given("a card for a plain tool") {
        `when`("it carries no detail") {
            then("the head is the tool alone") {
                RowText.tool(
                    ToolCallItem(itemId = "i", tool = "read_file", status = ItemStatus.Completed)
                ) shouldBe "read_file"
            }
        }
        `when`("it carries a detail") {
            then("the head names both") {
                RowText.tool(
                    ToolCallItem(
                        itemId = "i",
                        tool = "git",
                        detail = "status --short",
                        status = ItemStatus.Completed
                    )
                ) shouldBe "git  status --short"
            }
        }
        `when`("it failed") {
            then("the head says rejected") {
                RowText.tool(
                    ToolCallItem(
                        itemId = "i",
                        tool = "read_file",
                        status = ItemStatus.Failed,
                        ok = false
                    )
                ) shouldBe "read_file  rejected"
            }
        }
        `when`("it is still open") {
            then("the head says running") {
                RowText.tool(ToolCallItem(itemId = "i", tool = "read_file")) shouldBe "read_file  running\u2026"
            }
        }
    }

    given("a plan line") {
        `when`("the states are compared") {
            then("each has its own box") {
                RowText.plan(PlanEntryStatus.Completed, "wire the emitter") shouldBe "[x] wire the emitter"
                RowText.plan(PlanEntryStatus.InProgress, "draw cards") shouldBe "[~] draw cards"
                RowText.plan(PlanEntryStatus.Pending, "persist rows") shouldBe "[ ] persist rows"
            }
        }
    }

    given("a line of a unified patch") {
        `when`("it names the files") {
            then("it is a header and not a deletion, which is the one way a card could invent a change") {
                RowText.diffKind("--- a/src/App.kt") shouldBe DiffLine.Header
                RowText.diffKind("+++ b/src/App.kt") shouldBe DiffLine.Header
            }
        }
        `when`("it opens a hunk") {
            then("it is a hunk header") {
                RowText.diffKind("@@ -1,4 +1,4 @@") shouldBe DiffLine.Hunk
            }
        }
        `when`("it is a real change") {
            then("the sign decides") {
                RowText.diffKind("+val a = 1") shouldBe DiffLine.Added
                RowText.diffKind("-val a = 2") shouldBe DiffLine.Removed
            }
        }
        `when`("it is unchanged") {
            then("it reads as context") {
                RowText.diffKind(" fun main() {}") shouldBe DiffLine.Context
            }
        }
        `when`("a whole patch is counted both ways") {
            then("the card's classification agrees with the counts in its head") {
                val diff = """
                    --- a/src/App.kt
                    +++ b/src/App.kt
                    @@ -1,3 +1,3 @@
                     fun main() {}
                    -val a = 2
                    +val a = 1
                    +val b = 2
                """.trimIndent()
                val counted = RowText.diffKind("-val a = 2")
                val kinds = diff.lineSequence().map { RowText.diffKind(it) }.toList()
                val added = kinds.count { it == DiffLine.Added }
                val removed = kinds.count { it == DiffLine.Removed }
                val headers = kinds.count { it == DiffLine.Header }
                counted shouldBe DiffLine.Removed
                headers shouldBe 2
                (added to removed) shouldBe DiffStats.of(diff)
            }
        }
    }

    given("an excerpt of a tool's output") {
        `when`("the output spans several lines") {
            then("the excerpt is its first real line") {
                RowText.excerpt("\n\nfirst line\nsecond line\n") shouldBe "first line"
            }
        }
        `when`("that line is too long") {
            then("it is cut and marked, because an excerpt is a pointer and not the report") {
                RowText.excerpt("x".repeat(40), max = 10) shouldBe "${"x".repeat(10)}\u2026"
            }
        }
        `when`("the cut falls inside a word") {
            then("it cuts where it must, because an excerpt promises a limit and not a word") {
                RowText.excerpt("aaaa bbbb cccc", max = 6) shouldBe "aaaa b\u2026"
            }
        }
        `when`("the cut lands on a space") {
            then("the space is not left hanging before the mark") {
                RowText.excerpt("aaaa bbbb", max = 5) shouldBe "aaaa\u2026"
            }
        }
        `when`("there is nothing to say") {
            then("the excerpt is empty rather than a lonely mark") {
                RowText.excerpt("   \n\n  ") shouldBe ""
            }
        }
    }

    given("a command line") {
        `when`("it is put on a card") {
            then("it reads as an action taken") {
                RowText.command("ls -la") shouldBe "${'$'} ls -la"
            }
        }
    }

    given("a resolved approval") {
        `when`("a card keeps its answer after the buttons are gone") {
            then("a single grant reads as allowed once") {
                RowText.approval(ApprovalDecision.Accept) shouldBe "allowed once"
            }
            then("a session grant reads as always allowed, distinct from once") {
                RowText.approval(ApprovalDecision.AcceptForSession) shouldBe "always allowed"
            }
            then("a refusal reads as denied") {
                RowText.approval(ApprovalDecision.Decline) shouldBe "denied"
            }
            then("an abandoned turn reads as cancelled, not refused") {
                RowText.approval(ApprovalDecision.Cancel) shouldBe "cancelled"
            }
        }
    }
})
