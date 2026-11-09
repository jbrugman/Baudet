package dev.baudet.session

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

            then("only the kept turns are replayed, never a system summary row") {
                history.size shouldBe 2
                history.map { it.role } shouldBe listOf("user", "assistant")
                // The summary rides in the system instructions (see AppState.instructionsFor),
                // not here: the request builder drops system rows, so folding it in would
                // silently lose it after a compaction.
                (history.any { it.role == "system" }) shouldBe false
            }
            then("the kept turns follow in their original order") {
                history[0].content shouldBe "first question"
                history[1].content shouldBe "first answer"
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

    given("a transcript of the raw turns") {
        val session = SessionState(
            "s-5", "m", null,
            mutableListOf(Msg("user", "q1"), Msg("assistant", "a1"))
        )
        `when`("rendering it for display") {
            then("each turn is labeled by role and separated by a blank line") {
                session.transcript() shouldBe "user: q1\n\nassistant: a1\n\n"
            }
            then("the summary is not folded in, only the kept turns show") {
                session.summary = "an earlier summary"
                session.transcript() shouldBe "user: q1\n\nassistant: a1\n\n"
            }
        }
        `when`("there are no turns yet") {
            then("the transcript is empty") {
                SessionState("s-6", "m", null, mutableListOf()).transcript() shouldBe ""
            }
        }
    }
})
