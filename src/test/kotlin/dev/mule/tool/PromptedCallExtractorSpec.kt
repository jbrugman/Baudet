package dev.mule.tool

import dev.mule.api.FunctionCall
import dev.mule.api.Turn
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The extractor is the only thing that decides "this reply wants to act", so a
 * wrong read here is a wrong edit later. These cases pin the forgiving-but-safe
 * behaviour: it finds calls through prose and across several fences, yet a block
 * that will not parse is ignored rather than trusted, and text without the tag is
 * simply an answer.
 */
class PromptedCallExtractorSpec : BehaviorSpec({
    val extractor = PromptedCallExtractor()

    fun turn(text: String) = Turn(text, "r", null, null)

    given("a plain answer with no call block") {
        `when`("extracting") {
            val calls = extractor.extract(turn("The file looks fine, nothing to change."))
            then("it finds no calls") { calls.isEmpty() shouldBe true }
        }
    }

    given("a single call block in a fenced answer") {
        val text = "Sure.\n```mule-tool\n" +
            "{\"tool\":\"edit_file\",\"arguments\":{\"file\":\"a.txt\"}}\n```\nDone."
        `when`("extracting") {
            val calls = extractor.extract(turn(text))
            then("it reads one call with its name and arguments") {
                calls.size shouldBe 1
                calls[0].tool shouldBe "edit_file"
                (calls[0].arguments.containsKey("file")) shouldBe true
            }
        }
    }

    given("an array of call blocks") {
        val text = "```mule-tool\n" +
            "[{\"tool\":\"a\",\"arguments\":{}},{\"tool\":\"b\",\"arguments\":{}}]\n```"
        `when`("extracting") {
            val calls = extractor.extract(turn(text))
            then("it reads each call in order") {
                calls.size shouldBe 2
                calls[0].tool shouldBe "a"
                calls[1].tool shouldBe "b"
            }
        }
    }

    given("two separate call blocks") {
        val text = "```mule-tool\n{\"tool\":\"a\",\"arguments\":{}}```\nand\n```mule-tool\n{\"tool\":\"b\",\"arguments\":{}}```"
        `when`("extracting") {
            val calls = extractor.extract(turn(text))
            then("it reads both") { calls.map { it.tool } shouldBe listOf("a", "b") }
        }
    }

    given("a call block whose JSON is broken") {
        val text = "```mule-tool\n{\"tool\":\"a\", oops}\n```"
        `when`("extracting") {
            val calls = extractor.extract(turn(text))
            then("it skips the bad block instead of failing") { calls.isEmpty() shouldBe true }
        }
    }

    given("a native function call with no fence") {
        val native = Turn(
            "thinking", "r", null, null,
            functionCalls = listOf(FunctionCall("call_9", "edit_file", "{\"file\":\"a.txt\"}"))
        )
        `when`("extracting") {
            val calls = extractor.extract(native)
            then("it reads the native call with id, name and parsed arguments") {
                calls.size shouldBe 1
                calls[0].tool shouldBe "edit_file"
                calls[0].callId shouldBe "call_9"
                (calls[0].arguments.containsKey("file")) shouldBe true
            }
        }
    }

    given("a native call and a prompted block in one turn") {
        val tick = "\u0060\u0060\u0060"
        val text = tick + "mule-tool\n" +
            "{\"tool\":\"edit_file\",\"arguments\":{\"file\":\"b.txt\"}}\n" + tick
        val both = Turn(
            text, "r", null, null,
            functionCalls = listOf(FunctionCall("call_9", "native_tool", "{}"))
        )
        `when`("extracting") {
            val calls = extractor.extract(both)
            then("it lists the native call first and the prompted block after") {
                calls.map { it.tool } shouldBe listOf("native_tool", "edit_file")
            }
        }
    }

})
