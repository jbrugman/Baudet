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

    /*
     * The same complaint also carries the number the app is missing: how large this model's window
     * actually is. Reading it turns a refusal into knowledge, so the next turn is guarded against the
     * truth. A limit is only ever taken from next to the words that name a limit, because these bodies
     * also print the count that overflowed and a breakdown of it, and a limit read from those would be
     * wrong in the direction that matters most: too high, so the next request fails identically.
     */
    given("a refusal that names the window it hit") {
        `when`("reading the claimed window") {
            then("the vLLM wording gives the limit, not the count that overflowed") {
                BackendException(
                    400, "Prompt is too long: 129737 tokens > 128000 maximum context length"
                ).claimedContextTokens shouldBe 128_000
            }
            then("the OpenAI wording gives the window, not the requested or breakdown numbers") {
                BackendException(
                    400,
                    "This model's maximum context length is 128072 tokens. However, you " +
                        "requested 130000 tokens (120000 in messages, 8000 in completion)."
                ).claimedContextTokens shouldBe 128_072
            }
            then("a grouped number survives its separators") {
                BackendException(
                    400, "context_length_exceeded: maximum context length is 1,047,576 tokens"
                ).claimedContextTokens shouldBe 1_047_576
            }
            then("a context window named in passing is read too") {
                BackendException(
                    400, "context_length_exceeded: context window of 131072 exceeded"
                ).claimedContextTokens shouldBe 131_072
            }
        }
    }

    given("a refusal that names no window") {
        `when`("reading the claimed window") {
            then("an overflow without a limit stays unknown rather than guessed") {
                BackendException(400, "input is too long for this model").claimedContextTokens shouldBe null
                BackendException(400, "Prompt is too long: 129737 tokens > 128000 maximum")
                    .claimedContextTokens shouldBe null
            }
            then("a 400 about something else names nothing at all") {
                BackendException(400, """{"error":{"message":"unknown field: top_p"}}""")
                    .claimedContextTokens shouldBe null
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
