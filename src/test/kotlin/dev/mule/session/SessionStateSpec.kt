package dev.mule.session

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class SessionStateSpec : BehaviorSpec({

    given("a session holding a summary and two turns") {
        val session = SessionState(
            sessionId = "s-1",
            model = "m",
            previousResponseId = "resp_1",
            messages = mutableListOf(Msg("user", "first question"), Msg("assistant", "first answer"))
        )
        session.summary = "decisions taken earlier"

        `when`("building the prompt history") {
            val history = session.promptHistory()

            then("the summary leads as a system message, so replay still carries it") {
                history.size shouldBe 3
                history.first().role shouldBe "system"
                history.first().content.contains("decisions taken earlier") shouldBe true
            }
            then("the kept turns follow in their original order") {
                history[1].content shouldBe "first question"
                history[2].content shouldBe "first answer"
            }
        }
    }

    given("a session without a summary") {
        val session = SessionState("s-2", "m", null, mutableListOf(Msg("user", "only this")))

        `when`("building the prompt history") {
            then("it is the transcript itself, with nothing prepended") {
                session.promptHistory().size shouldBe 1
            }
        }
    }

    given("an estimate of the context in use") {
        `when`("measuring") {
            then("it grows when content is added") {
                val session = SessionState("s-3", "m", null, mutableListOf())
                val empty = session.estimateTokens()
                session.messages.add(Msg("user", "a".repeat(400)))
                (session.estimateTokens() > empty) shouldBe true
            }
            then("an active summary counts toward it, because it is sent") {
                val session = SessionState("s-4", "m", null, mutableListOf())
                val before = session.estimateTokens()
                session.summary = "b".repeat(400)
                (session.estimateTokens() > before) shouldBe true
            }
        }
    }
})
