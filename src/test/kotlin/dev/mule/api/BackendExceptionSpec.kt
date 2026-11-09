package dev.mule.api

import dev.mule.i18n.Key
import dev.mule.i18n.Texts
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * One HTTP 400 means "fix your request" and another means "this conversation is over the
 * window", and only the second has a remedy the user can reach for. The wording differs per
 * backend family, so the recognition lives here and is pinned against the bodies those backends
 * really send.
 */
class BackendExceptionSpec : BehaviorSpec({

    given("a vLLM-style complaint about the prompt length") {
        val body = "{\"error\":{\"message\":\"Prompt is too long: 129737 tokens > \" +\n            \"128000 maximum context length\",\"code\":\"context_length_exceeded\"}}"
        `when`("asking whether it is a context overflow") {
            then("it is recognized, in either of its two wordings") {
                BackendException(400, body).contextOverflow shouldBe true
                BackendException.looksLikeContextOverflow(
                    "This model's maximum context length is 128000 tokens"
                ) shouldBe true
                BackendException(400, "input is too long for this model").contextOverflow shouldBe true
            }
        }
    }

    given("a 400 about something else entirely") {
        `when`("asking whether it is a context overflow") {
            then("it is not, so the user is not sent off to compact a short chat") {
                BackendException(400, """{"error":{"message":"unknown field: top_p"}}""").contextOverflow shouldBe false
                BackendException.looksLikeContextOverflow(null) shouldBe false
            }
        }
    }

    given("the exception as the user reads it") {
        val e = BackendException(400, "Prompt is too long: 129737 tokens > 128000 maximum")
        `when`("rendering it") {
            then("the status and the body are still there for diagnostics") {
                e.status shouldBe 400
                (e.message!!.contains(Texts[Key.HttpFromBackend].take(4))) shouldBe true
                (e.message!!.contains("129737")) shouldBe true
            }
        }
    }
})
