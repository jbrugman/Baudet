package dev.mule.session

import dev.mule.config.ContextStrategy
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ContextTrimmerSpec : BehaviorSpec({

    fun msgs(n: Int): List<Msg> = buildList {
        repeat(n) { add(Msg(if (it % 2 == 0) "user" else "assistant", "x".repeat(300))) }
    }

    fun isNote(m: Msg): Boolean = m.content.startsWith("[") && m.content.endsWith("]")

    given("an unknown window") {
        val history = msgs(6) + Msg("user", "current question")
        `when`("trimming with a zero budget") {
            val out = ContextTrimmer.trim(history, ContextStrategy.TruncateAtMiddle, 0L)
            then("nothing is invented, so the whole conversation is left as it was") {
                out shouldBe history
            }
        }
    }

    given("a conversation that already fits") {
        val history = msgs(3) + Msg("user", "short")
        `when`("trimming generously") {
            val out = ContextTrimmer.trim(history, ContextStrategy.TruncateAtMiddle, 1_000_000L)
            then("nothing is dropped, so no placeholder note is inserted") {
                out shouldBe history
                out.none { isNote(it) } shouldBe true
            }
        }
    }

    given("a rolling window over a long conversation") {
        val prior = msgs(10)
        val current = Msg("user", "the latest question")
        val history = prior + current
        val out = ContextTrimmer.trim(history, ContextStrategy.RollingWindow, 600L)
        `when`("the newest tail is kept") {
            then("the current turn always survives") {
                out.last() shouldBe current
            }
            then("only the newest contiguous prior turns are kept") {
                val kept = out.filterNot { isNote(it) }.dropLast(1)
                kept shouldBe prior.takeLast(kept.size)
            }
            then("a note stands in for what fell off the front") {
                out.first() shouldBe out.firstOrNull { isNote(it) }
                out.any { isNote(it) } shouldBe true
            }
        }
    }

    given("truncate at middle over a long conversation") {
        val prior = msgs(10)
        val current = Msg("user", "the latest question")
        val history = prior + current
        val out = ContextTrimmer.trim(history, ContextStrategy.TruncateAtMiddle, 600L)
        `when`("the middle is dropped") {
            then("the current turn always survives") {
                out.last() shouldBe current
            }
            then("the first turn is kept as an anchor") {
                out.first() shouldBe prior.first()
            }
            then("the newest turns are kept alongside the anchor") {
                out[out.size - 2] shouldBe prior.last()
            }
            then("a note marks the omitted middle") {
                out.count { isNote(it) } shouldBe 1
            }
            then("the middle really was removed") {
                (out.filterNot { isNote(it) }.size < prior.size + 1) shouldBe true
            }
        }
    }

    given("a current turn too large to leave any room") {
        val history = msgs(6) + Msg("user", "y".repeat(10_000))
        `when`("the budget cannot hold even the prompt") {
            val out = ContextTrimmer.trim(history, ContextStrategy.RollingWindow, 1L)
            then("only the prompt is sent, since dropping it would answer nothing") {
                out shouldBe listOf(history.last())
            }
        }
    }
})
