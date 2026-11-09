package dev.baudet.tool

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The runner never lets tool trouble reach the loop as an exception: an unknown
 * name and a handler that throws both come back as a model-facing result, so the
 * model can read the failure and try again. A known handler's result, undo handle
 * included, is passed straight through.
 */
class ToolRunnerSpec : BehaviorSpec({
    val ctx = ToolContext(root = null, writeGranted = false, approve = { _, _ -> true })
    val emptyArgs = buildJsonObject { put("x", 1) }

    val good = object : Tool {
        override val name = "good"
        override val description = "always works"
        override val parameters = buildJsonObject { put("type", "object") }
        override fun execute(args: JsonObject, ctx: ToolContext) =
            ToolResult(ok = true, output = "handled")
    }
    val boom = object : Tool {
        override val name = "boom"
        override val description = "always throws"
        override val parameters = buildJsonObject { put("type", "object") }
        override fun execute(args: JsonObject, ctx: ToolContext): ToolResult = error("kaboom")
    }
    val runner = ToolRunner(ToolRegistry(listOf(good, boom)))

    given("a known tool") {
        `when`("running its call") {
            val result = runner.run(ToolCall("good", emptyArgs), ctx)
            then("it returns the handler result unchanged") {
                result.ok shouldBe true
                result.output shouldBe "handled"
            }
        }
    }

    given("a call naming a tool that is not registered") {
        `when`("running it") {
            val result = runner.run(ToolCall("ghost", emptyArgs), ctx)
            then("it reports the miss without throwing") {
                result.ok shouldBe false
                (result.output.contains("ghost")) shouldBe true
            }
        }
    }

    given("a tool whose handler throws") {
        `when`("running it") {
            val result = runner.run(ToolCall("boom", emptyArgs), ctx)
            then("it turns the crash into a failed result") {
                result.ok shouldBe false
                (result.output.contains("failed")) shouldBe true
            }
        }
    }
})
