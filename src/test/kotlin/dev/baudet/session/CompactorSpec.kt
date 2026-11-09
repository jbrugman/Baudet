package dev.baudet.session

import dev.baudet.api.Turn
import dev.baudet.config.AppConfig
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

    given("a stream that closed without a terminal event") {
        val turn = Turn(
            text = "only a partial summary arrived before the stream ended",
            responseId = null,
            usage = null,
            finalEvent = "stream_end_after_3 events",
            incompleteReason = null
        )
        `when`("asking whether it may replace the older turns") {
            then("it is refused, because nothing proved the answer finished") {
                Compactor.acceptsSummary(turn) shouldBe false
            }
        }
    }

    /**
     * The ceiling on one summarization call is what protects the request. A conversation that needs
     * folding is by definition one that no longer fits a call, so a ceiling that lands at 0 -- or at
     * any value the fold then ignores -- sends the oldest turn whole and the Compact button fails
     * with the same context error it was pressed to clear. These pin that the shipped default, where
     * Settings holds 0 to mean "the app decides", still yields a real ceiling.
     */
    given("the shipped default, where Settings holds 0 to mean the app decides") {
        val compactor = Compactor(AppConfig())
        `when`("asking how much text one summarization call may carry") {
            then("the ceiling is the full default, not zero") {
                compactor.maxSourceChars() shouldBe 48_000
            }
            then("it is never zero, which is the value that reaches the backend uncapped") {
                (compactor.maxSourceChars() > 0) shouldBe true
            }
        }
    }

    given("a budget typed in Settings") {
        `when`("it sits at the model's window") {
            then("it cannot raise the ceiling above what one call can carry") {
                Compactor(AppConfig(contextBudgetTokens = 128_000)).maxSourceChars() shouldBe 48_000
            }
        }
        `when`("it is smaller than the default") {
            then("it lowers the ceiling, because a budget is a cap") {
                Compactor(AppConfig(contextBudgetTokens = 8_000)).maxSourceChars() shouldBe 24_000
            }
        }
        `when`("it is small enough to leave only a fragment of a turn") {
            then("the floor holds, or a summary of a fragment would clear turns it never read") {
                Compactor(AppConfig(contextBudgetTokens = 100)).maxSourceChars() shouldBe 4_000
            }
        }
    }

    /**
     * The default ceiling is written for a large model. On a small one it is the bug: 48k characters
     * is sixteen thousand tokens, which a 4k window refuses outright, so the fold overflows on the
     * one conversation that cannot afford a refused call. The window the turn guard already uses has
     * to bound the fold too.
     */
    given("a model whose real window is smaller than the default ceiling") {
        `when`("asking how much text one summarization call may carry") {
            then("half the window bounds the batch, leaving room for the summary it writes") {
                Compactor(AppConfig(), windowTokens = 4_096).maxSourceChars() shouldBe 6_144
            }
            then("a 128k window still gets the default, since that is the smaller bound") {
                Compactor(AppConfig(), windowTokens = 128_000).maxSourceChars() shouldBe 48_000
            }
            then("a window too small for a usable batch keeps the floor, or a fragment is accepted") {
                Compactor(AppConfig(), windowTokens = 1_024).maxSourceChars() shouldBe 4_000
            }
            then("a typed budget below both still wins, because a cap is a cap") {
                Compactor(AppConfig(contextBudgetTokens = 1_000), windowTokens = 128_000)
                    .maxSourceChars() shouldBe 4_000
            }
        }
    }

    /**
     * The ceiling is what keeps a fold from losing text. A turn bigger than one summarization call
     * can only reach the model in pieces, and a piece it never read must never be cleared behind a
     * summary -- that is the silent loss this whole path exists to prevent. The fold stops at such
     * a turn and keeps it verbatim. These flow tests drive that decision through the real compact()
     * with an injected fake summarizer, so the guard is pinned end to end and not only in the plan.
     */
    given("a session whose only turn is larger than one call can carry") {
        val huge = Msg("tool", "O".repeat(60_000))
        val state = SessionState("s", "m", null, mutableListOf(huge))
        val seen = mutableListOf<String>()
        val compactor = Compactor(
            AppConfig(),
            summarize = { src, _ ->
                seen.add(src)
                Turn("a summary", null, null, "response.completed")
            }
        )
        `when`("compacting with nothing kept verbatim") {
            val result = compactor.compact(state, keepLast = 0) {}
            then("nothing folds and the oversized turn survives untouched") {
                result shouldBe null
                state.messages.size shouldBe 1
                state.messages[0].content shouldBe huge.content
            }
            then("the summarizer is never called, because the guard stops before any cut") {
                seen shouldBe emptyList()
            }
        }
    }

    given("an oversized turn holding back an otherwise foldable prefix") {
        val m0 = Msg("user", "first question")
        val m1 = Msg("assistant", "first answer")
        val huge = Msg("tool", "O".repeat(60_000))
        val state = SessionState(
            "s", "m", null,
            mutableListOf(m0, m1, huge, Msg("user", "keep one"), Msg("assistant", "keep two"))
        )
        val seen = mutableListOf<String>()
        val compactor = Compactor(
            AppConfig(),
            summarize = { src, _ ->
                seen.add(src)
                Turn("the summary", null, null, "response.completed")
            }
        )
        `when`("compacting and keeping the last two turns") {
            val result = compactor.compact(state, keepLast = 2) {}
            then("the foldable prefix is summarized once, whole, never cut") {
                seen.size shouldBe 1
                seen[0].contains("first question") shouldBe true
                seen[0].contains("first answer") shouldBe true
                seen[0].contains("cut here") shouldBe false
            }
            then("only the folded turns are cleared and the oversized turn is kept in full") {
                result shouldBe "the summary"
                state.summary shouldBe "the summary"
                state.messages.size shouldBe 3
                state.messages[0].content shouldBe huge.content
                state.messages[1].content shouldBe "keep one"
                state.messages[2].content shouldBe "keep two"
            }
        }
    }
})
