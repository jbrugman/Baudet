package dev.mule.tool

import dev.mule.api.Turn
import dev.mule.session.Msg
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    fun callText(tool: String) = "editing now\n```mule-tool\n{\"tool\":\"$tool\",\"arguments\":{}}\n```"
    fun seed(text: String) = listOf(Msg("user", text))

    class Scripted(private val replies: List<Turn>) : TurnSource {
        var i = 0
        val seen = mutableListOf<List<Msg>>()
        override fun nextTurn(history: List<Msg>, previousResponseId: String?, onDelta: (String) -> Unit, onReasoning: (String) -> Unit): Turn {
            seen.add(history.toList())
            return replies[i.coerceAtMost(replies.lastIndex)].also { i++ }
        }
    }
    class AlwaysCall(private val reply: Turn) : TurnSource {
        override fun nextTurn(history: List<Msg>, previousResponseId: String?, onDelta: (String) -> Unit, onReasoning: (String) -> Unit): Turn = reply
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
})
