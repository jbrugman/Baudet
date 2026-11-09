package dev.baudet.api

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class SseFramerSpec : BehaviorSpec({

    given("a stream that names the event only on the event: line") {
        val events = SseFramer.frame(
            sequenceOf(
                "event: response.output_text.delta",
                "data: {\"delta\":\"hi\"}",
                "",
            )
        ).toList()

        `when`("framed") {
            then("the name survives") {
                events.single().event shouldBe "response.output_text.delta"
            }
        }
    }

    given("a payload split over two data: lines") {
        val events = SseFramer.frame(sequenceOf("data: {\"a\":", "data: 1}", "")).toList()

        `when`("framed") {
            then("it is one event, not two broken ones") {
                events.size shouldBe 1
            }
            then("the lines join with a newline") {
                events.single().data shouldBe "{\"a\":\n1}"
            }
        }
    }

    given("the keep-alive and control lines a real server interleaves") {
        val events = SseFramer.frame(
            sequenceOf(
                ": keep-alive",
                "id: 12",
                "retry: 3000",
                "data: {\"type\":\"response.created\"}",
                "",
            )
        ).toList()

        `when`("framed") {
            then("comments, id and retry never become events") {
                events.size shouldBe 1
            }
        }
    }

    given("a data: line") {
        val events = SseFramer.frame(sequenceOf("data: {\"delta\":\" x \"}", "")).toList()

        `when`("framed") {
            then("only the single optional leading space is stripped") {
                events.single().data shouldBe "{\"delta\":\" x \"}"
            }
        }
    }

    given("a stream ending on the terminator and without a trailing blank line") {
        val events = SseFramer.frame(
            sequenceOf("data: {\"type\":\"response.output_text.done\"}", "", "data: [DONE]")
        ).toList()

        `when`("framed") {
            then("the terminator is dropped") {
                events.size shouldBe 1
            }
            then("an event before the terminator is still delivered") {
                events.single().data shouldBe "{\"type\":\"response.output_text.done\"}"
            }
        }
    }

    given("a blank line with no data behind it") {
        `when`("framed") {
            then("nothing is emitted") {
                SseFramer.frame(sequenceOf("", "", "data: [DONE]")).toList().size shouldBe 0
            }
        }
    }
})
