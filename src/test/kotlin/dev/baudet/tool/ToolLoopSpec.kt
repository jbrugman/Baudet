package dev.baudet.tool

import dev.baudet.api.FunctionCall
import dev.baudet.api.Turn
import dev.baudet.session.Msg
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The loop is the orchestrator, so these drive it with a scripted model instead of
 * a backend: a reply with no call ends it, a call runs and its observation goes back
 * into the next turn, a cap stops a model that would never stop, and a failed tool
 * is fed back rather than fatal. A fake no-op tool keeps the loop off the disk so
 * only the loop's own logic is under test.
 */
class ToolLoopSpec : BehaviorSpec({
    val ctx = ToolContext(root = null, writeGranted = false, approve = { _, _ -> true })

    val noop = object : Tool {
        override val name = "noop"
        override val description = "does nothing"
        override val parameters = buildJsonObject { put("type", "object") }
        override fun execute(args: JsonObject, ctx: ToolContext) = ToolResult(ok = true, output = "noop ok")
    }
    val registry = ToolRegistry(listOf(noop))
    val extractor = PromptedCallExtractor()

    fun turn(text: String, id: String) = Turn(text, id, null, null)
    fun callText(tool: String) = "editing now\n```baudet-tool\n{\"tool\":\"$tool\",\"arguments\":{}}\n```"
    fun seed(text: String) = listOf(Msg("user", text))

    class Scripted(private val replies: List<Turn>) : TurnSource {
        var i = 0
        val seen = mutableListOf<List<Msg>>()
        val images = mutableListOf<String?>()
        override fun nextTurn(history: List<Msg>, previousResponseId: String?, tools: String?, onDelta: (String) -> Unit, onReasoning: (String) -> Unit, image: String?): Turn {
            seen.add(history.toList())
            images.add(image)
            return replies[i.coerceAtMost(replies.lastIndex)].also { i++ }
        }
    }
    class AlwaysCall(private val reply: Turn) : TurnSource {
        override fun nextTurn(history: List<Msg>, previousResponseId: String?, tools: String?, onDelta: (String) -> Unit, onReasoning: (String) -> Unit, image: String?): Turn = reply
    }

    given("a model that answers without calling a tool") {
        val source = Scripted(listOf(turn("nothing to do", "r1")))
        val loop = ToolLoop(source, registry, extractor, maxIterations = 4)
        `when`("running") {
            val outcome = loop.run(seed("hello"), ctx = ctx)
            then("it completes with that answer") {
                (outcome is LoopOutcome.Completed) shouldBe true
                (outcome as LoopOutcome.Completed).finalText shouldBe "nothing to do"
            }
            then("it hands the caller the whole final Turn") {
                val done = outcome as LoopOutcome.Completed
                done.finalTurn.responseId shouldBe "r1"
                done.finalTurn.incompleteReason shouldBe null
            }
            then("it records one empty step and stores the assistant message") {
                outcome.steps.size shouldBe 1
                outcome.steps[0].calls.isEmpty() shouldBe true
                outcome.messages.map { it.role } shouldBe listOf("assistant")
            }
        }
    }

    given("a final answer the backend truncated") {
        val source = Scripted(listOf(Turn("ran out", "r9", null, null, incompleteReason = "max_output_tokens")))
        val loop = ToolLoop(source, registry, extractor, maxIterations = 4)
        `when`("running") {
            val outcome = loop.run(seed("go"), ctx = ctx)
            then("the completed outcome keeps that truncation on the final turn") {
                (outcome as LoopOutcome.Completed).finalTurn.incompleteReason shouldBe "max_output_tokens"
            }
        }
    }

    given("a model that calls once, then answers") {
        val source = Scripted(listOf(turn(callText("noop"), "r1"), turn("all done", "r2")))
        val loop = ToolLoop(source, registry, extractor, maxIterations = 4)
        `when`("running") {
            val outcome = loop.run(seed("fix it"), ctx = ctx)
            then("it completes with the final answer") {
                (outcome is LoopOutcome.Completed) shouldBe true
                (outcome as LoopOutcome.Completed).finalText shouldBe "all done"
            }
            then("the assistant turn and the tool observation both go back into history") {
                val roles = outcome.messages.map { it.role }
                roles shouldBe listOf("assistant", "user", "assistant")
                (outcome.messages[1].content.contains("noop")) shouldBe true
                (outcome.messages[1].content.contains("succeeded")) shouldBe true
            }
            then("the second turn already saw the observation") {
                (source.seen[1].any { it.content.contains("noop ok") }) shouldBe true
            }
        }
    }

    given("a model that would call forever") {
        val source = AlwaysCall(turn(callText("noop"), "r"))
        val loop = ToolLoop(source, registry, extractor, maxIterations = 2)
        `when`("running") {
            val outcome = loop.run(seed("go"), ctx = ctx)
            then("the cap stops it as CapReached after two steps") {
                (outcome is LoopOutcome.CapReached) shouldBe true
                outcome.steps.size shouldBe 2
                outcome.steps.all { it.calls.size == 1 } shouldBe true
            }
        }
    }

    given("a model that calls a tool that is not available") {
        val source = Scripted(listOf(turn(callText("ghost"), "r1"), turn("recovered", "r2")))
        val loop = ToolLoop(source, registry, extractor, maxIterations = 4)
        `when`("running") {
            val outcome = loop.run(seed("go"), ctx = ctx)
            then("the miss is fed back and the loop still reaches a final answer") {
                (outcome is LoopOutcome.Completed) shouldBe true
                (outcome as LoopOutcome.Completed).finalText shouldBe "recovered"
                outcome.steps[0].results[0].ok shouldBe false
                (outcome.messages[1].content.contains("no tool named")) shouldBe true
            }
        }
    }

    given("a native function call the backend already returned") {
        val callTurn = Turn("working", "r1", null, null,
            functionCalls = listOf(FunctionCall("call_1", "noop", "{}")))
        val source = Scripted(listOf(callTurn, turn("all done", "r2")))
        val loop = ToolLoop(source, registry, extractor, maxIterations = 4)
        `when`("running") {
            val outcome = loop.run(seed("go"), ctx = ctx)
            then("the native call runs and its observation names the call id") {
                (outcome is LoopOutcome.Completed) shouldBe true
                (outcome as LoopOutcome.Completed).finalText shouldBe "all done"
                (outcome.messages[1].content.contains("call_1")) shouldBe true
                (outcome.messages[1].content.contains("succeeded")) shouldBe true
            }
        }
    }

    given("a chained session that already outgrew the context ceiling") {
        // Under a server session the backend replays and grows its own context, so the client
        // cannot trim its way around a doomed request; the loop must stop before spending a turn.
        val source = Scripted(listOf(turn(callText("noop"), "r1")))
        val loop = ToolLoop(
            source, registry, extractor, maxIterations = 4,
            chainResponseId = true, contextCeiling = 10
        )
        `when`("running with a transcript already over the ceiling") {
            val outcome = loop.run(
                listOf(Msg("user", "x".repeat(3_000))), previousResponseId = "prev", ctx = ctx
            )
            then("it stops as CapReached naming the context limit") {
                (outcome is LoopOutcome.CapReached) shouldBe true
                (outcome as LoopOutcome.CapReached).incompleteReason shouldBe "context_limit"
                outcome.steps.isEmpty() shouldBe true
            }
            then("it never fired the doomed request") {
                source.seen.isEmpty() shouldBe true
            }
        }
    }

    given("an over-long transcript that is not chained") {
        // The client replays and trims what it sends when not chaining, so a big transcript is
        // its own problem to bound: the ceiling must be ignored and the turn must still run.
        val source = Scripted(listOf(turn("nothing to do", "r1")))
        val loop = ToolLoop(
            source, registry, extractor, maxIterations = 4,
            chainResponseId = false, contextCeiling = 10
        )
        `when`("running with the same big transcript") {
            val outcome = loop.run(listOf(Msg("user", "x".repeat(3_000))), ctx = ctx)
            then("the ceiling is ignored and the request still goes out") {
                (outcome is LoopOutcome.Completed) shouldBe true
                source.seen.isNotEmpty() shouldBe true
            }
        }
    }

    given("a model that only answers after the iteration cap") {
        val source = Scripted(listOf(turn(callText("noop"), "r1"), turn("settled", "r2")))
        val loop = ToolLoop(source, registry, extractor, maxIterations = 1)
        `when`("running") {
            val outcome = loop.run(seed("go"), ctx = ctx)
            then("the answer still lands but the outcome flags the cap") {
                (outcome is LoopOutcome.Completed) shouldBe true
                (outcome as LoopOutcome.Completed).finalText shouldBe "settled"
                outcome.capReached shouldBe true
            }
        }
    }

    given("a turn that attaches an image") {
        val source = Scripted(listOf(turn(callText("noop"), "r1"), turn("done", "r2")))
        val loop = ToolLoop(source, registry, extractor, maxIterations = 4)
        `when`("the loop runs with an image on the prompt") {
            loop.run(seed("look at this"), ctx = ctx, image = "data:image/png;base64,AAA")
            then("the image rides only the first model request") {
                source.images shouldBe listOf("data:image/png;base64,AAA", null)
            }
        }
    }

    given("a pure-read batch that every call agrees to fan out") {
        // A barrier of two only clears when both runs are live at the same instant; a serial loop
        // would time out on the first, so reaching it twice is the proof the batch really fanned out.
        val barrier = CyclicBarrier(2)
        fun readTool(nm: String, sleepMs: Long) = object : Tool {
            override val name = nm
            override val description = "reads"
            override val parameters = buildJsonObject { put("type", "object") }
            override fun canRunParallel(args: JsonObject, ctx: ToolContext) = true
            override fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
                val met = try { barrier.await(2, TimeUnit.SECONDS); true } catch (_: Exception) { false }
                Thread.sleep(sleepMs)
                return ToolResult(ok = true, output = "$nm met=$met")
            }
        }
        val reg = ToolRegistry(listOf(readTool("slow", 150), readTool("fast", 10)))
        val two = "reads\n```baudet-tool\n[{\"tool\":\"slow\"},{\"tool\":\"fast\"}]\n```"
        val source = Scripted(listOf(turn(two, "r1"), turn("done", "r2")))
        val loop = ToolLoop(source, reg, extractor, maxIterations = 4)
        `when`("running") {
            val outcome = loop.run(seed("read both"), ctx = ctx)
            then("both runs met the sibling, so the batch executed at once") {
                (outcome is LoopOutcome.Completed) shouldBe true
                outcome.steps[0].results.map { it.output.contains("met=true") } shouldBe listOf(true, true)
            }
            then("cards and observations still come back in call order despite fast ending first") {
                outcome.steps[0].results.map { it.output.contains("slow") } shouldBe listOf(true, false)
                outcome.messages[1].content.contains("slow") shouldBe true
                outcome.messages[2].content.contains("fast") shouldBe true
            }
        }
    }

    given("a batch that mixes a fan-out-able call with a serial one") {
        // One non-parallel call keeps the whole batch serial, so the live count must never exceed one.
        val live = AtomicInteger(0)
        val peak = AtomicInteger(0)
        fun tracked(nm: String, parallel: Boolean) = object : Tool {
            override val name = nm
            override val description = "t"
            override val parameters = buildJsonObject { put("type", "object") }
            override fun canRunParallel(args: JsonObject, ctx: ToolContext) = parallel
            override fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
                val now = live.incrementAndGet()
                peak.updateAndGet { k -> if (now > k) now else k }
                Thread.sleep(80)
                live.decrementAndGet()
                return ToolResult(ok = true, output = nm)
            }
        }
        val reg = ToolRegistry(listOf(tracked("p", true), tracked("s", false)))
        val two = "```baudet-tool\n[{\"tool\":\"p\"},{\"tool\":\"s\"}]\n```"
        val source = Scripted(listOf(turn(two, "r1"), turn("done", "r2")))
        val loop = ToolLoop(source, reg, extractor, maxIterations = 4)
        `when`("running") {
            val outcome = loop.run(seed("mixed"), ctx = ctx)
            then("the mixed batch never ran two calls at once") {
                (outcome is LoopOutcome.Completed) shouldBe true
                peak.get() shouldBe 1
            }
        }
    }

    given("a reasoning model that parks a turn in its reasoning channel") {
        // The reported bug: a turn that puts everything in reasoning leaves the answer channel
        // blank with no call, which used to read as a final answer and did nothing -- the user had
        // to nudge. The loop now retries a blank turn, so a model that would have acted still does.
        val source = Scripted(listOf(turn("", "r1"), turn(callText("noop"), "r2"), turn("all done", "r3")))
        val loop = ToolLoop(source, registry, extractor, maxIterations = 4)
        `when`("the first turn comes back blank") {
            val outcome = loop.run(seed("generate an image"), ctx = ctx)
            then("the blank turn is retried and the call still runs to an answer") {
                (outcome is LoopOutcome.Completed) shouldBe true
                (outcome as LoopOutcome.Completed).finalText shouldBe "all done"
            }
            then("the blank reply left no empty assistant message on record") {
                outcome.messages.map { it.role } shouldBe listOf("assistant", "user", "assistant")
                outcome.messages.all { it.content.isNotBlank() } shouldBe true
            }
            then("the loop really asked the model again before acting") {
                (source.seen.size >= 2) shouldBe true
            }
        }
    }

    given("a model that stays blank turn after turn") {
        // The retry must be bounded: a model that never produces content cannot spin the loop. It
        // ends blank after the small ceiling, and the blank reply itself is what reaches the caller.
        val source = Scripted(listOf(turn("", "r1")))
        val loop = ToolLoop(source, registry, extractor, maxIterations = 4)
        `when`("every turn is blank") {
            val outcome = loop.run(seed("go"), ctx = ctx)
            then("it stops as Completed but blank, after a bounded number of asks") {
                (outcome is LoopOutcome.Completed) shouldBe true
                (outcome as LoopOutcome.Completed).finalText.isBlank() shouldBe true
                source.seen.size shouldBe 3
            }
        }
    }

    given("a blank turn followed by a plain answer") {
        val source = Scripted(listOf(turn("", "r1"), turn("all done", "r2")))
        val loop = ToolLoop(source, registry, extractor, maxIterations = 4)
        `when`("running") {
            val outcome = loop.run(seed("go"), ctx = ctx)
            then("the blank turn is retried and then the real answer lands") {
                (outcome is LoopOutcome.Completed) shouldBe true
                (outcome as LoopOutcome.Completed).finalText shouldBe "all done"
                outcome.messages.map { it.role } shouldBe listOf("assistant")
                source.seen.size shouldBe 2
            }
        }
    }

    /**
     * A tool observation goes to the model channel bounded, so one giant diff or grep cannot bloat
     * every later turn nor exceed what one summarization call carries and strand compaction. The
     * full output still lands in the transcript row through the sink path, which [capModelText]
     * never touches. These pin that the model-facing text is capped and that a small one is left
     * exactly as the tool wrote it.
     */
    given("a tool observation larger than the ceiling one turn may carry") {
        `when`("capping it for the model channel") {
            then("the model sees a head and a tail around a truncation marker, not the whole") {
                val capped = capModelText("x".repeat(40_000), 1_000)
                (capped.length <= 1_016) shouldBe true
                capped.contains("output truncated") shouldBe true
                capped.startsWith("xxx") shouldBe true
                capped.endsWith("xxx") shouldBe true
            }
            then("an observation under the ceiling passes through byte for byte") {
                capModelText("short output", 1_000) shouldBe "short output"
            }
        }
    }
})
