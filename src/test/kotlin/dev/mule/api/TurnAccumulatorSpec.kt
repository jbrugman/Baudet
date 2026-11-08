package dev.mule.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The fixtures are payloads recorded from oMLX serving
 * Qwen3.8-Flash-Next-oQ4e-mtp on 2026-11-08, trimmed to the fields that matter.
 * They are real rather than written from the OpenAI documentation on purpose:
 * the point of this class is to match what local backends actually send.
 */
class TurnAccumulatorSpec : BehaviorSpec({

    fun fed(vararg payloads: String): TurnAccumulator {
        val acc = TurnAccumulator()
        payloads.forEach { acc.feed(SseEvent(null, it)) }
        return acc
    }

    given("a normal turn from a reasoning model") {
        val acc = fed(
            """{"type":"response.created","response":{"id":"resp_f973ffc062dc45de80863225","status":"in_progress"}}""",
            """{"type":"response.reasoning_summary_text.delta","item_id":"rs_a193","summary_index":0,"delta":"\nThinking"}""",
            """{"type":"response.output_text.delta","item_id":"msg_8c81","content_index":0,"delta":"\n\nkomkommer"}""",
            """{"type":"response.output_text.done","item_id":"msg_8c81","text":"\n\nkomkommer"}""",
            """{"type":"response.completed","response":{"id":"resp_f973ffc062dc45de80863225","usage":{"input_tokens":67,"output_tokens":252,"total_tokens":319}}}"""
        )

        `when`("the stream is finished") {
            val turn = acc.finish()

            then("the answer is the output_text deltas and nothing else") {
                turn.text shouldBe "\n\nkomkommer"
            }
            then("reasoning is kept, but apart from the answer") {
                turn.reasoning shouldBe "\nThinking"
                turn.text.contains("Thinking") shouldBe false
            }
            then("the response id and both token counts come off the wire") {
                turn.responseId shouldBe "resp_f973ffc062dc45de80863225"
                turn.usage?.prompt shouldBe 67L
                turn.usage?.completion shouldBe 252L
            }
            then("a clean finish reports no truncation") {
                turn.finalEvent shouldBe "response.completed"
                turn.incompleteReason shouldBe null
            }
            then("the finished text is not appended a second time behind the deltas") {
                turn.text.contains("komkommerkomkommer") shouldBe false
            }
        }
    }

    given("a reasoning model whose budget ran out before the answer started") {
        val acc = fed(
            """{"type":"response.created","response":{"id":"resp_9464"}}""",
            """{"type":"response.reasoning_summary_text.delta","delta":"\nThinking"}""",
            """{"type":"response.output_text.done","item_id":"msg_ef1c","text":""}""",
            """{"type":"response.incomplete","response":{"id":"resp_9464","usage":{"input_tokens":67,"output_tokens":48},"incomplete_details":{"reason":"max_output_tokens"}}}"""
        )

        `when`("the stream is finished") {
            val turn = acc.finish()

            then("the empty answer is not passed off as a finished turn") {
                turn.text shouldBe ""
                turn.finalEvent shouldBe "response.incomplete"
            }
            then("the reason is named, because only one of them is fixed by budget") {
                turn.incompleteReason shouldBe "max_output_tokens"
            }
            then("the reasoning already spent is still returned") {
                turn.reasoning shouldBe "\nThinking"
            }
        }
    }

    given("a backend that truncates without naming why") {
        val acc = fed(
            """{"type":"response.incomplete","response":{"id":"resp_x"}}"""
        )

        `when`("the stream is finished") {
            then("the finish event stands in as the reason") {
                acc.finish().incompleteReason shouldBe "response.incomplete"
            }
        }
    }

    given("a backend that never sends output deltas") {
        val acc = fed(
            """{"type":"response.output_text.done","text":"hello"}""",
            """{"type":"response.completed","response":{"id":"r"}}"""
        )

        `when`("the stream is finished") {
            then("the finished text is adopted instead of showing an empty answer") {
                acc.finish().text shouldBe "hello"
            }
        }
    }

    given("both deltas and the finished text") {
        val acc = fed(
            """{"type":"response.output_text.delta","delta":"hi"}""",
            """{"type":"response.output_text.done","text":"hi"}""",
            """{"type":"response.completed","response":{"id":"r"}}"""
        )

        `when`("the stream is finished") {
            then("the text appears once") {
                acc.finish().text shouldBe "hi"
            }
        }
    }

    given("an event this client has never seen") {
        val acc = fed("""{"type":"response.mystery_event","foo":1}""")

        `when`("the stream is finished") {
            then("it is counted rather than fatal") {
                acc.events["response.mystery_event"] shouldBe 1
            }
            then("the turn ends without inventing an answer") {
                val turn = acc.finish()
                turn.text shouldBe ""
                turn.finalEvent shouldBe "stream_end_after_1 events"
            }
        }
    }

    given("a payload that is not JSON") {
        val acc = fed("""upstream connect error or disconnect/reset before headers""")

        `when`("the stream is finished") {
            then("it lands in the census instead of crashing the turn") {
                acc.events["(not-json)"] shouldBe 1
            }
        }
    }

    given("a payload with no event name anywhere") {
        val acc = fed("""{"delta":"orphan"}""")

        `when`("the stream is finished") {
            then("it is bucketed as untyped and not guessed at") {
                acc.events["(no-type)"] shouldBe 1
                acc.finish().text shouldBe ""
            }
        }
    }

    given("an event type carried only by the event: line") {
        val acc = TurnAccumulator()
        acc.feed(SseEvent("response.output_text.delta", """{"delta":"x"}"""))

        `when`("the stream is finished") {
            then("the name is still used to find the delta") {
                acc.finish().text shouldBe "x"
            }
        }
    }

    given("the chat completions shape as a fallback transport") {
        val acc = fed(
            """{"object":"chat.completion.chunk","choices":[{"delta":{"content":"par"}}]}""",
            """{"object":"chat.completion.chunk","choices":[{"delta":{"content":"rot"}}]}"""
        )

        `when`("the stream is finished") {
            then("content accumulates across chunks") {
                acc.finish().text shouldBe "parrot"
            }
        }
    }

    given("an error event on the stream") {
        val acc = fed("""{"type":"error","error":{"message":"model not loaded"}}""")

        `when`("the stream is finished") {
            then("the turn fails instead of reporting an empty answer") {
                shouldThrow<BackendException> { acc.finish() }
            }
        }
    }

    given("a stream that carried nothing at all") {
        `when`("the stream is finished") {
            then("it is reported as a transport failure") {
                shouldThrow<BackendException> { TurnAccumulator().finish() }
            }
        }
    }

    given("a completed response whose output carries a native call") {
        val acc = fed(
            """{"type":"response.created","response":{"id":"resp_fc"}}""",
            """{"type":"response.completed","response":{"id":"resp_fc","output":[{"type":"function_call","call_id":"call_abc","name":"edit_file","arguments":"{\"file\":\"README.md\"}"}]}}"""
        )

        `when`("the stream is finished") {
            val turn = acc.finish()
            then("the native call is captured with its id, name and raw arguments") {
                turn.functionCalls.size shouldBe 1
                turn.functionCalls[0].callId shouldBe "call_abc"
                turn.functionCalls[0].name shouldBe "edit_file"
                (turn.functionCalls[0].arguments.contains("README.md")) shouldBe true
            }
        }
    }

})
