package dev.baudet.thread

import dev.baudet.api.Turn
import dev.baudet.api.Usage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The emitter is the only place that turns "a producer did something" into rows, so these fix
 * the three things a renderer and a future protocol both depend on.
 *
 * Identifiers are scoped by the conversation, because a machine with room to spare runs several
 * conversations at once and a listener must be able to address a row without reading its text.
 * The wire methods are asserted as literals rather than derived, because they are the app-server's
 * own spellings: renaming one here is a silent protocol break that no compiler would catch. And
 * every path that ends a turn -- the clean one, a Stop, a failure -- has to leave no row spinning,
 * since a producer that returns early is exactly what an interrupted local model does.
 */
class ThreadEmitterSpec : BehaviorSpec({

    fun collector(): Pair<ThreadEmitter, MutableList<ThreadEvent>> {
        val events = mutableListOf<ThreadEvent>()
        return ThreadEmitter("thr-1") { events += it } to events
    }

    fun methods(events: List<ThreadEvent>): List<String> = events.map { it.wireMethod }

    given("a turn opened on one conversation") {
        val (emitter, events) = collector()
        emitter.turnStarted()
        emitter.userMessage("fix it")
        emitter.reasoningDelta("thinking")
        emitter.textDelta("hello ")
        emitter.textDelta("world")
        emitter.replyCompleted("hello world")
        emitter.turnCompleted(Turn("hello world", "resp-1", Usage(10, 5, 15), "response.completed"))

        `when`("the turn is replayed as events") {
            then("every event names the conversation and the turn it belongs to") {
                events.map { it.threadId }.distinct() shouldBe listOf("thr-1")
                events.map { it.turnId }.distinct() shouldBe listOf("thr-1-t1")
            }
            then("the methods are the app-server's own spellings, in order") {
                methods(events) shouldBe listOf(
                    "turn/started",
                    "item/started", "item/completed",
                    "item/started", "item/reasoning/textDelta",
                    "item/completed",
                    "item/started", "item/agentMessage/delta", "item/agentMessage/delta",
                    "item/completed",
                    "thread/tokenUsage/updated",
                    "turn/completed"
                )
            }
            then("ids are prefixed with the conversation, so one thread's id cannot pass for another's") {
                events.filterIsInstance<ItemStarted>().map { it.item.itemId } shouldBe
                    listOf("thr-1-i1", "thr-1-i2", "thr-1-i3")
            }
            then("the reasoning row closes at the first answer chunk, not at the end of the turn") {
                val closed = events.filterIsInstance<ItemCompleted>().first { it.item is ReasoningItem }
                closed.item.status shouldBe ItemStatus.Completed
                events.indexOf(closed) shouldBe
                    events.indexOfFirst { it is ItemDelta && it.kind == ItemKind.Reasoning } + 1
            }
            then("the completed reply carries the whole answer, not only what streamed") {
                val done = events.filterIsInstance<ItemCompleted>().last { it.item is AgentMessageItem }
                (done.item as AgentMessageItem).text shouldBe "hello world"
            }
            then("the accounting leaves before the turn closes, so a truncated reply is still judgeable") {
                methods(events).indexOf("thread/tokenUsage/updated") shouldBe
                    methods(events).indexOf("turn/completed") - 1
            }
        }
    }

    given("a producer that never announced a turn") {
        val (emitter, events) = collector()
        emitter.textDelta("hi")

        `when`("the first chunk arrives") {
            then("a turn is opened and the chunk lands inside it") {
                methods(events) shouldBe listOf("turn/started", "item/started", "item/agentMessage/delta")
                events.map { it.turnId }.distinct() shouldBe listOf("thr-1-t1")
            }
        }
    }

    given("a reply that stays prose-free because the model asked to act instead") {
        val (emitter, events) = collector()
        emitter.turnStarted()
        emitter.replyStarted()
        emitter.commandStarted("ls", null, 30)
        emitter.commandCompleted(0, false, 1, "ok", true)
        emitter.turnCompleted(Turn("", null, null, null))

        `when`("the announced reply never received content") {
            then("no empty assistant row is invented between the step and the card") {
                events.filter { it is ItemStarted && it.item is AgentMessageItem } shouldBe emptyList()
                methods(events) shouldBe listOf("turn/started", "item/started", "item/completed", "turn/completed")
            }
        }
    }

    given("a command that ran") {
        val (emitter, events) = collector()
        emitter.turnStarted()
        emitter.commandStarted("mvnw test", null, 30)
        emitter.commandCompleted(
            exitCode = 2,
            timedOut = false,
            durationMs = 41,
            output = "two failures",
            ok = false,
            cwd = "/real/project"
        )

        `when`("the card is closed") {
            then("it carries what really happened, including a non-zero exit as a failure") {
                val done = events.filterIsInstance<ItemCompleted>().single().item as CommandExecutionItem
                done.status shouldBe ItemStatus.Failed
                done.exitCode shouldBe 2
                done.timedOut shouldBe false
                done.durationMs shouldBe 41
                done.cwd shouldBe "/real/project"
                done.output shouldBe "two failures"
            }
        }
    }

    given("a command that timed out, which is not the same as exit code zero") {
        val (emitter, events) = collector()
        emitter.turnStarted()
        emitter.commandStarted("sleep 999", null, 1)
        emitter.commandCompleted(null, true, 1_000, "", false)

        `when`("the run is reported") {
            then("the exit code stays absent instead of being rounded to zero") {
                val done = events.filterIsInstance<ItemCompleted>().single().item as CommandExecutionItem
                done.exitCode shouldBe null
                done.timedOut shouldBe true
                done.status shouldBe ItemStatus.Failed
            }
        }
    }

    given("a turn that wrote a file") {
        val (emitter, events) = collector()
        emitter.turnStarted()
        emitter.fileChangeStarted("a.txt")
        emitter.fileChangeCompleted("a.txt", FileChangeKind.Update, "--- a/a.txt\n+++ b/a.txt\n@@\n+x\n-y\n", true)
        emitter.turnCompleted(Turn("done", null, null, null))

        `when`("the turn closes") {
            then("the card counts the patch without counting its headers") {
                val done = events.filterIsInstance<ItemCompleted>().single { it.item is FileChangeItem }
                    .item as FileChangeItem
                done.additions shouldBe 1
                done.deletions shouldBe 1
            }
            then("the turn offers one accumulated diff to review") {
                val diff = events.filterIsInstance<TurnDiffUpdated>().single()
                diff.files.map { it.path } shouldBe listOf("a.txt")
                diff.additions shouldBe 1
                diff.deletions shouldBe 1
                methods(events) shouldBe listOf(
                    "turn/started", "item/started", "item/completed",
                    "turn/diff/updated", "turn/completed"
                )
            }
        }
    }

    given("a turn stopped while its reply was still streaming") {
        val (emitter, events) = collector()
        emitter.turnStarted()
        emitter.replyStarted()
        emitter.textDelta("half an")
        emitter.turnInterrupted()

        `when`("the stop closes the turn") {
            then("the row keeps what it reached and is marked interrupted, not completed") {
                val done = events.filterIsInstance<ItemCompleted>().single().item as AgentMessageItem
                done.text shouldBe "half an"
                done.status shouldBe ItemStatus.Interrupted
            }
            then("the turn says why it did not finish") {
                events.filterIsInstance<TurnCompleted>().single().incompleteReason shouldBe "interrupted"
            }
        }
    }

    given("a turn that failed") {
        val (emitter, events) = collector()
        emitter.turnStarted()
        emitter.turnFailed("backend refused the window")

        `when`("the failure is reported") {
            then("it appears as a row where it happened, and the turn says it failed") {
                val error = events.filterIsInstance<ItemCompleted>().single().item as ErrorItem
                error.message shouldBe "backend refused the window"
                error.status shouldBe ItemStatus.Failed
                events.filterIsInstance<TurnCompleted>().single().incompleteReason shouldBe "failed"
            }
        }
    }

    given("two conversations whose streams reach one listener") {
        val events = mutableListOf<ThreadEvent>()
        val first = ThreadEmitter("thr-a") { events += it }
        val second = ThreadEmitter("thr-b") { events += it }
        first.turnStarted()
        second.turnStarted()
        first.textDelta("from a")
        second.textDelta("from b")

        `when`("both are interleaved") {
            then("each row stays addressable to its own conversation") {
                events.filter { it.threadId == "thr-a" }.filterIsInstance<ItemStarted>().single()
                    .item.itemId shouldBe "thr-a-i1"
                events.filter { it.threadId == "thr-b" }.filterIsInstance<ItemStarted>().single()
                    .item.itemId shouldBe "thr-b-i1"
            }
        }
    }

    given("a tool that returned a picture") {
        val (emitter, events) = collector()
        emitter.turnStarted()
        emitter.toolCallStarted("mcp__qwen__generate", "")
        emitter.toolCallCompleted(ok = true, output = "1 image(s) returned; shown to the user")
        emitter.imageCompleted("mcp__qwen__generate", "image/png", "aGVsbG8=")

        `when`("the picture is reported") {
            then("it is its own row, started and completed in one beat, marked done") {
                val done = events.filterIsInstance<ItemCompleted>().last().item as ImageItem
                done.kind shouldBe ItemKind.Image
                done.status shouldBe ItemStatus.Completed
                done.tool shouldBe "mcp__qwen__generate"
                done.mimeType shouldBe "image/png"
                done.base64 shouldBe "aGVsbG8="
            }
            then("the image row opened after the tool row, both reaching the same stream") {
                methods(events) shouldBe listOf(
                    "turn/started", "item/started", "item/completed",
                    "item/started", "item/completed"
                )
                events.filterIsInstance<ItemStarted>().map { it.item.kind } shouldBe
                    listOf(ItemKind.ToolCall, ItemKind.Image)
            }
        }
    }

    given("the vocabulary this package publishes") {
        `when`("it is read as wire names") {
            then("the item tags are the app-server's, and only our own extension is marked as ours") {
                ItemKind.CommandExecution.wireName shouldBe "commandExecution"
                ItemKind.FileChange.wireName shouldBe "fileChange"
                ItemKind.AgentMessage.wireName shouldBe "agentMessage"
                ItemKind.Reasoning.wireName shouldBe "reasoning"
                ItemKind.ToolCall.wireName shouldBe "baudet/toolCall"
                ItemKind.Image.wireName shouldBe "baudet/image"
            }
            then("statuses, decisions and plan lines spell the way the protocol does") {
                ItemStatus.InProgress.wireName shouldBe "inProgress"
                ItemStatus.Completed.wireName shouldBe "completed"
                ApprovalDecision.Accept.wireName shouldBe "accept"
                ApprovalDecision.AcceptForSession.wireName shouldBe "acceptForSession"
                ApprovalDecision.Decline.wireName shouldBe "decline"
                ApprovalDecision.Cancel.wireName shouldBe "cancel"
                PlanEntryStatus.InProgress.wireName shouldBe "inProgress"
            }
            then("each content type streams under its own method") {
                deltaMethod(ItemKind.AgentMessage) shouldBe "item/agentMessage/delta"
                deltaMethod(ItemKind.Reasoning) shouldBe "item/reasoning/textDelta"
                deltaMethod(ItemKind.CommandExecution) shouldBe "item/commandExecution/outputDelta"
                deltaMethod(ItemKind.FileChange) shouldBe "item/fileChange/outputDelta"
                deltaMethod(ItemKind.ToolCall) shouldBe "baudet/item/delta"
            }
            then("an approval carries the method of the request it answers") {
                ApprovalRequested("t", "u", "i", ApprovalRequest.RunCommand("ls", null, null)).wireMethod shouldBe
                    "item/commandExecution/requestApproval"
                ApprovalRequested("t", "u", "i", ApprovalRequest.ApplyPatch("a.txt", null)).wireMethod shouldBe
                    "item/fileChange/requestApproval"
                ApprovalResolved("t", "u", "i", ApprovalDecision.Accept).wireMethod shouldBe "serverRequest/resolved"
            }
        }
        `when`("a diff is counted") {
            then("the header lines are not content") {
                DiffStats.of("--- a/x\n+++ b/x\n@@ -1 +1 @@\n+plus\n-minus\n context") shouldBe (1 to 1)
            }
            then("nothing and an empty patch both count as nothing") {
                DiffStats.of(null) shouldBe (0 to 0)
                DiffStats.of("") shouldBe (0 to 0)
            }
        }
    }

    given("a write that borrows the command approval path from a tool-call card") {
        val (emitter, events) = collector()
        emitter.turnStarted()
        emitter.toolCallStarted("git", "commit")
        // A git write runs through the RunCommand approval path but its open card is a tool call.
        val itemId = emitter.requestApproval(ApprovalRequest.RunCommand("git commit -m x", null, 15))

        `when`("the request is published") {
            then("it homes on the open tool card, not on a fresh orphan row") {
                val started = events.filterIsInstance<ItemStarted>().last().item as ToolCallItem
                itemId shouldBe started.itemId
            }
            then("the ApprovalRequested names that same item") {
                events.filterIsInstance<ApprovalRequested>().single().itemId shouldBe itemId
            }
        }
    }
})
