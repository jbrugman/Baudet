package dev.mule.tool

import dev.mule.session.Msg
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The transcript is what leaves toward the backend and, through replayed history, what
 * a chat-tuned model can imitate. A leaked control token must never survive into either,
 * and the assistant turns must not be replayed: with previous_response_id off the backend
 * still needs the human text, and re-feeding the assistant only re-feeds its own leakage.
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
            then("only prior user turns are replayed, never the assistant") {
                prior.map { it.role } shouldBe listOf("user")
                prior[0].content shouldBe "first"
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
})
