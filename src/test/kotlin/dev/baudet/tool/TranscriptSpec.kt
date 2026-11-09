package dev.baudet.tool

import dev.baudet.config.ContextStrategy
import dev.baudet.session.Msg
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The transcript is what leaves toward the backend and, through replayed history, what
 * a chat-tuned model can imitate. A leaked control token must never survive into either.
 * Every prior turn is replayed now, assistant turns included, so a run keeps its working
 * context when previous_response_id is off; scrubbing covers all of them, so replaying
 * the assistant cannot re-feed the leakage.
 * The token is written as code points so this source never holds the literal it removes.
 */
class TranscriptSpec : BehaviorSpec({
    val token = "\u003c\u007cim_end\u007c\u003e"

    given("text carrying a leaked control token") {
        `when`("scrubbing it") {
            val out = Transcript.scrub("done" + token + "more")
            then("the token is gone and the prose around it stays") {
                out shouldBe "donemore"
            }
        }
    }

    given("a transcript mixing roles and a leaked token") {
        val history = listOf(
            Msg("user", "first" + token),
            Msg("assistant", "answer"),
            Msg("user", "second")
        )
        `when`("preparing one backend call") {
            val (prior, userText) = Transcript.forCall(history)
            then("every prior turn is replayed, assistant included, each scrubbed") {
                prior.map { it.role } shouldBe listOf("user", "assistant")
                prior.map { it.content } shouldBe listOf("first", "answer")
            }
            then("the newest message is the current user input") {
                userText shouldBe "second"
            }
            then("no bracket survives anywhere, so the control token cannot leak") {
                (prior.all { !it.content.contains("\u003c") }) shouldBe true
                (userText.contains("\u003c")) shouldBe false
            }
        }
    }

    /**
     * A long tool run re-sends every observation on every iteration, so without a ceiling it
     * grows until the backend refuses the whole run. These pin the ceiling: it keeps a
     * contiguous newest tail, says what it dropped, and never drops anything that fits.
     */
    given("prior turns that do not fit into one call") {
        val prior = (1..10).map { Msg("user", "m$it-" + "x".repeat(90)) }
        `when`("fitting them into a small ceiling") {
            val kept = Transcript.fit(prior, 300)
            then("the newest turns survive, in their original order") {
                (kept.size < prior.size) shouldBe true
                kept.drop(1).map { it.content } shouldBe prior.takeLast(kept.size - 1).map { it.content }
            }
            then("one marker stands in for the turns that had to go, and names how many") {
                val dropped = prior.size - (kept.size - 1)
                kept.size shouldBe prior.size - dropped + 1
                (kept.first().content.contains("[$dropped earlier messages")) shouldBe true
            }
        }
    }

    given("prior turns that fit") {
        val prior = listOf(Msg("user", "short"), Msg("assistant", "answer"))
        `when`("fitting them") {
            then("nothing is dropped and no marker is invented") {
                Transcript.fit(prior, 100_000) shouldBe prior
            }
            then("a ceiling of zero means the guard is off, so the whole run replays") {
                Transcript.fit(prior, 0) shouldBe prior
            }
        }
    }

    given("an oversized single turn inside the prior turns") {
        val prior = listOf(Msg("user", "small"), Msg("user", "y".repeat(10_000)))
        `when`("fitting it into a ceiling smaller than that turn") {
            val kept = Transcript.fit(prior, 50)
            then("nothing is kept, but the call still goes out with a marker") {
                kept.size shouldBe 1
                (kept.first().content.contains("[2 earlier messages")) shouldBe true
            }
        }
    }

    given("a tool turn that appended two observations after one answer") {
        val history = listOf(
            Msg("user", "the prompt"),
            Msg("assistant", "first answer"),
            Msg("user", "observation one"),
            Msg("user", "observation two")
        )
        `when`("choosing the current input under a server session") {
            val current = Transcript.currentInput(history, underServerSession = true)
            then("both new observations reach the backend, not only the last") {
                (current.contains("observation one")) shouldBe true
                (current.contains("observation two")) shouldBe true
            }
        }
        `when`("choosing the current input without a server session") {
            then("only the newest message is the input, since the rest replays") {
                Transcript.currentInput(history, underServerSession = false) shouldBe "observation two"
            }
        }
    }

    given("a tool loop whose transcript grows past the configured budget") {
        // A round trip appends an assistant turn and a tool observation. With a budget far below
        // the transcript the loop must cap every request to that budget, not let the later ones
        // fall back to the fixed replay ceiling -- the defect the loop used to carry.
        val budgetTokens = 2_000L
        fun growing(rounds: Int): List<Msg> = buildList {
            add(Msg("user", "the prompt"))
            repeat(rounds) { i ->
                add(Msg("assistant", "ans$i-" + "y".repeat(1_200)))
                add(Msg("user", "obs$i-" + "x".repeat(1_200)))
            }
        }
        val history = growing(12)
        fun charCost(list: List<Msg>): Int = list.sumOf { it.content.length + it.role.length + 8 }
        `when`("splitting one request under a rolling window") {
            val (prior, userText) = Transcript.forLoop(
                history, ContextStrategy.RollingWindow, budgetTokens, underServerSession = false
            )
            then("the newest observation is the current input, so it is never dropped") {
                userText shouldBe "obs11-" + "x".repeat(1_200)
            }
            then("the replayed prior is bounded to the budget, not the fixed 48k ceiling") {
                (charCost(prior) < 10_000) shouldBe true
            }
            then("a note stands in for the older turns trimmed out of this request") {
                (prior.any { it.content.startsWith("[") && it.content.endsWith("]") }) shouldBe true
            }
        }
        `when`("splitting the same transcript under StopAtLimit") {
            then("the budget is ignored, so the whole transcript reaches the fixed clamp instead") {
                val (stopPrior, _) = Transcript.forLoop(
                    history, ContextStrategy.StopAtLimit, budgetTokens, underServerSession = false
                )
                val (trimPrior, _) = Transcript.forLoop(
                    history, ContextStrategy.RollingWindow, budgetTokens, underServerSession = false
                )
                (charCost(stopPrior) > charCost(trimPrior)) shouldBe true
            }
        }
    }

})
