package dev.mule.session

import dev.mule.api.Turn
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Compaction is destructive: the older turns are cleared once a summary replaces them, so a
 * summary the backend cut short must never trigger that. [Compactor.acceptsSummary] is the one
 * decision separating a complete summary from a truncated or empty one. It is tested directly,
 * without any network, so the rule is pinned at the point it is enforced.
 */
class CompactorSpec : BehaviorSpec({

    given("a complete, non-empty summary") {
        val turn = Turn(
            text = "kept facts",
            responseId = null,
            usage = null,
            finalEvent = "response.completed",
            incompleteReason = null
        )
        `when`("asking whether it may replace the older turns") {
            then("it is accepted") { Compactor.acceptsSummary(turn) shouldBe true }
        }
    }

    given("a summary the backend cut off at the token limit") {
        val turn = Turn(
            text = "a partial summary that stops mid sentence",
            responseId = null,
            usage = null,
            finalEvent = "response.incomplete",
            incompleteReason = "max_output_tokens"
        )
        `when`("asking whether it may replace the older turns") {
            then("it is refused so nothing is dropped for an incomplete record") {
                Compactor.acceptsSummary(turn) shouldBe false
            }
        }
    }

    given("an empty summary") {
        val turn = Turn(
            text = "   ",
            responseId = null,
            usage = null,
            finalEvent = "response.completed",
            incompleteReason = null
        )
        `when`("asking whether it may replace the older turns") {
            then("it is refused") { Compactor.acceptsSummary(turn) shouldBe false }
        }
    }
})
