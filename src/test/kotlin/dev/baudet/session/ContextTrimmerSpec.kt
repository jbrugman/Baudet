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
                ContextTrimmer.bound(history, ContextStrategy.RecentMessages, 0L) shouldBe history
            }
        }
    }

    given("an unknown window") {
        val history = msgs(6) + Msg("user", "current question")
        `when`("trimming with a zero budget") {
            val out = ContextTrimmer.trim(history, ContextStrategy.RecentMessages, 0L)
            then("nothing is invented, so the whole conversation is left as it was") {
                out shouldBe history
            }
        }
    }

    given("a conversation that already fits") {
        val history = msgs(3) + Msg("user", "short")
        `when`("trimming generously") {
            val out = ContextTrimmer.trim(history, ContextStrategy.RecentMessages, 1_000_000L)
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

    given("keep last turns over a long conversation, budget not binding") {
        val prior = msgs(10)
        val current = Msg("user", "the latest question")
        val history = prior + current
        // A generous budget so the only thing that limits the window is the count: exactly the last
        // three messages (two prior plus the current turn) go out, and nothing older than them.
        val out = ContextTrimmer.trim(history, ContextStrategy.RecentMessages, 1_000_000L, keepRecent = 3)
        `when`("the window is the recent tail only") {
            then("the current turn always survives") {
                out.last() shouldBe current
            }
            then("exactly the last keep-recent messages are sent") {
                out shouldBe history.takeLast(3)
            }
            then("no count-based trimming inserts a note, because it is a clean tail") {
                out.any { isNote(it) } shouldBe false
            }
        }
    }

    given("keep last turns against the drift the old anchor caused") {
        // Distinct content per turn, so matching a message by value really means matching that turn:
        // the guarantee is that the opening turn is not resent, where trim-the-middle pinned it and
        // pulled the model back to a subject the conversation had already left behind.
        val turns = (1..10).map {
            Msg(if (it % 2 == 1) "user" else "assistant", "turn $it " + "x".repeat(300))
        }
        val current = Msg("user", "the latest question")
        val history = turns + current
        val out = ContextTrimmer.trim(history, ContextStrategy.RecentMessages, 1_000_000L, keepRecent = 4)
        `when`("only a recent tail is sent") {
            then("the opening turn is not pinned at the front as an anchor") {
                out.first() shouldNotBe turns.first()
            }
            then("no long-finished opening turn is resurrected into the request") {
                out.contains(turns.first()) shouldBe false
            }
            then("the window still ends on the current turn") {
                out.last() shouldBe current
            }
        }
    }

    given("keep last turns asked down to one") {
        val prior = msgs(10)
        val current = Msg("user", "the latest question")
        val history = prior + current
        val out = ContextTrimmer.trim(history, ContextStrategy.RecentMessages, 1_000_000L, keepRecent = 1)
        `when`("the smallest window is requested") {
            then("only the current turn goes out") {
                out shouldBe listOf(current)
            }
        }
    }

    given("keep last turns where the count is larger than the conversation") {
        val history = msgs(3) + Msg("user", "short")
        val out = ContextTrimmer.trim(history, ContextStrategy.RecentMessages, 1_000_000L, keepRecent = 15)
        `when`("the conversation is shorter than the count") {
            then("the whole conversation is sent, nothing is dropped") {
                out shouldBe history
            }
        }
    }

    given("keep last turns where the recent tail still busts the budget") {
        // Even a count-bounded window is fitted to the token budget: a huge newest turn is clamped and
        // the older turns of that window roll off, so a recent tail cannot itself overflow the model.
        val history = msgs(4) + Msg("tool", "O".repeat(20_000))
        val out = ContextTrimmer.trim(history, ContextStrategy.RecentMessages, 4_000L, keepRecent = 3)
        `when`("the recent tail cannot fit the window") {
            then("the oversized newest turn is clamped, never dropped") {
                out.last().content.contains("truncated") shouldBe true
                (out.last().content.length <= 4_000 * 3) shouldBe true
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
