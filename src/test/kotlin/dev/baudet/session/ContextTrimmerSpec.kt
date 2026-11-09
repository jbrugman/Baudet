package dev.baudet.session

import dev.baudet.config.ContextStrategy
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class ContextTrimmerSpec : BehaviorSpec({

    fun msgs(n: Int): List<Msg> = buildList {
        repeat(n) { add(Msg(if (it % 2 == 0) "user" else "assistant", "x".repeat(300))) }
    }

    fun isNote(m: Msg): Boolean = m.content.startsWith("[") && m.content.endsWith("]")

    given("the single whole-versus-trimmed owner") {
        val history = msgs(6) + Msg("user", "current question")
        `when`("bounding under StopAtLimit") {
            then("the whole transcript comes back untouched whatever the budget") {
                ContextTrimmer.bound(history, ContextStrategy.StopAtLimit, 1L) shouldBe history
            }
        }
        `when`("bounding under a trimming strategy") {
            then("it delegates to the trim, so a tiny budget cuts the window") {
                ContextTrimmer.bound(history, ContextStrategy.RollingWindow, 1L) shouldNotBe history
            }
            then("a zero budget leaves it whole, because the window is unknown") {
                ContextTrimmer.bound(history, ContextStrategy.TruncateAtMiddle, 0L) shouldBe history
            }
        }
    }

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
        // The newest turn is what the model must react to, so it is never dropped, but a turn larger
        // than the window is now clamped to a head slice that fits and labelled as incomplete, instead
        // of being sent whole and busting the request it is the only content of.
        val history = msgs(6) + Msg("user", "y".repeat(10_000))
        `when`("the budget cannot hold even the prompt") {
            val out = ContextTrimmer.trim(history, ContextStrategy.RollingWindow, 1L)
            then("the oversized turn is clamped to the window, never dropped") {
                out.size shouldBe 1
                out[0].role shouldBe "user"
                (out[0].content.length < 10_000) shouldBe true
                (out[0].content.contains("truncated")) shouldBe true
            }
        }
    }

    given("one oversized observation under a small budget") {
        // A single large tool result was the reported way to blow the window: it is always kept, so
        // kept whole it overran a 4000-token budget by five times. It must come back clamped to the
        // budget with a truncation note, rather than pushing the request past the model limit.
        val history = msgs(4) + Msg("tool", "O".repeat(20_000))
        `when`("trimming for a window the observation cannot fit in") {
            val out = ContextTrimmer.trim(history, ContextStrategy.RollingWindow, 4_000L)
            then("the observation is clamped to the budget, not sent whole") {
                out.size shouldBe 1
                (out[0].content.length <= 4_000 * 3) shouldBe true
                (out[0].content.contains("truncated")) shouldBe true
            }
        }
    }
    given("a lone message that busts the window") {
        // The old early return for a one-message history handed an oversized turn straight through,
        // which is the exact failure the clamping exists to stop: a single large tool result on its
        // own still has to fit, so it is clamped to the window and labelled, not forwarded whole.
        val history = listOf(Msg("user", "y".repeat(20_000)))
        `when`("trimming a single turn that cannot fit") {
            val out = ContextTrimmer.trim(history, ContextStrategy.RollingWindow, 4_000L)
            then("the lone oversized message is clamped to the budget") {
                out.size shouldBe 1
                out[0].role shouldBe "user"
                (out[0].content.length <= 4_000 * 3) shouldBe true
                (out[0].content.contains("truncated")) shouldBe true
            }
        }
    }

    given("a lone message that already fits") {
        // A single turn small enough for the window is the whole request, so it comes back untouched
        // and the prior-keeping paths are never asked to run on an empty history.
        val history = listOf(Msg("user", "short question"))
        `when`("trimming generously") {
            val out = ContextTrimmer.trim(history, ContextStrategy.RollingWindow, 1_000_000L)
            then("the whole single message is left as it was") {
                out shouldBe history
            }
        }
    }

})
