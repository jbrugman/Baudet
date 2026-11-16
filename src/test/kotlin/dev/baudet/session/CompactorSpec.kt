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
     * The other half of one summarization call: what the summary itself may spend. A constant here
     * was a bound on a reasoning model's thinking as much as on its summary, because the fold sends
     * no reasoning field, and a model that ends its answer at that bound is refused -- which leaves
     * the conversation unfolded and the user staring at a button that appears to do nothing.
     */
    given("the output bound one summary call may ask for") {
        `when`("deriving it from the model") {
            then("a model with no known window gets the ceiling, since nothing bounds it") {
                Compactor.summaryTokens(null) shouldBe 2_048
            }
            then("a large window still gets the ceiling, because a long summary is not the goal") {
                Compactor.summaryTokens(262_144) shouldBe 2_048
            }
            then("the ceiling is exactly a quarter of the window it was written for") {
                Compactor.summaryTokens(8_192) shouldBe 2_048
            }
            then("a window between floor and ceiling bounds it, so the fold cannot ask for output it has no room for") {
                // 7168/4 = 1792 sits between the floor and the ceiling, so this is the one range
                // where the window still wins over both and the bound is genuinely window-derived.
                Compactor.summaryTokens(7_168) shouldBe 1_792
            }
            then("a window too small to hold either half keeps the floor, or a reasoning model's thinking starves the summary") {
                // 1024/4 = 256 is below the floor, which exists so thinking and summary share the
                // bound with room left; the floor wins rather than starving the fold.
                Compactor.summaryTokens(1_024) shouldBe 1_536
            }
            then("an unknown window of 0 means unknown, not no room") {
                Compactor.summaryTokens(0) shouldBe 2_048
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
                result shouldBe CompactionOutcome.OversizedTurn(48_000)
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
                result shouldBe CompactionOutcome.Compacted("the summary")
                state.summary shouldBe "the summary"
                state.messages.size shouldBe 3
                state.messages[0].content shouldBe huge.content
                state.messages[1].content shouldBe "keep one"
                state.messages[2].content shouldBe "keep two"
            }
        }
    }

    /**
     * What a fold reports when it clears nothing, and why. The status line used to read "compaction
     * returned nothing" for all of these at once, which was untrue twice over: it sounded like an
     * outage when no request had even been made, and it hid the reason when an answer did come back
     * and could not be trusted. Each case is pinned to the signal the backend really sent, because
     * that signal is what tells the user whether to raise a budget, leave the transcript as it is,
     * or go look at the server -- and a refusal must still keep every turn, asserted alongside it.
     */
    given("a session with nothing before the kept tail") {
        val state = SessionState(
            "s", "m", null,
            mutableListOf(Msg("user", "one"), Msg("assistant", "two"))
        )
        var calls = 0
        val compactor = Compactor(
            AppConfig(),
            summarize = { _, _ ->
                calls++
                Turn("x", null, null, "response.completed")
            }
        )
        `when`("compacting while keeping both turns") {
            val result = compactor.compact(state, keepLast = 2) {}
            then("it says there was nothing to fold, rather than that a summary came back empty") {
                result shouldBe CompactionOutcome.NothingToFold
            }
            then("nothing is sent and nothing is touched") {
                calls shouldBe 0
                state.messages.size shouldBe 2
                state.summary shouldBe null
            }
        }
    }

    given("a summarizer whose answer was cut off at the output bound") {
        val state = SessionState(
            "s", "m", null,
            mutableListOf(
                Msg("user", "q1"), Msg("assistant", "a1"),
                Msg("user", "q2"), Msg("assistant", "a2")
            )
        )
        val compactor = Compactor(
            AppConfig(),
            summarize = { _, _ ->
                Turn(
                    "a summary that stops mid sentence", null, null, "response.incomplete",
                    incompleteReason = "max_output_tokens"
                )
            }
        )
        `when`("compacting and keeping the last two turns") {
            val result = compactor.compact(state, keepLast = 2) {}
            then("it names the truncation and the bound it hit, not just that nothing came back") {
                result shouldBe CompactionOutcome.SummaryRefused("incomplete: max_output_tokens (2048)")
            }
            then("every turn survives, because an incomplete record may not replace text") {
                state.messages.size shouldBe 4
                state.summary shouldBe null
            }
        }
    }

    given("a summarizer whose stream closed without a completion event") {
        val state = SessionState(
            "s", "m", null,
            mutableListOf(
                Msg("user", "q1"), Msg("assistant", "a1"),
                Msg("user", "q2"), Msg("assistant", "a2")
            )
        )
        val compactor = Compactor(
            AppConfig(),
            summarize = { _, _ ->
                Turn("a plausible summary", null, null, "stream_end_after_3 events")
            }
        )
        `when`("compacting and keeping the last two turns") {
            val result = compactor.compact(state, keepLast = 2) {}
            then("it names the event that did arrive, since that is the only clue there is") {
                result shouldBe
                    CompactionOutcome.SummaryRefused("no response.completed, got stream_end_after_3 events")
            }
            then("nothing is cleared, because a chat turn forgives a missing terminal event and a fold may not") {
                state.messages.size shouldBe 4
                state.summary shouldBe null
            }
        }
    }

    given("a summarizer that answers with nothing but whitespace") {
        val state = SessionState(
            "s", "m", null,
            mutableListOf(
                Msg("user", "q1"), Msg("assistant", "a1"),
                Msg("user", "q2"), Msg("assistant", "a2")
            )
        )
        val compactor = Compactor(
            AppConfig(),
            summarize = { _, _ -> Turn("   ", null, null, "response.completed") }
        )
        `when`("compacting and keeping the last two turns") {
            val result = compactor.compact(state, keepLast = 2) {}
            then("the refusal says the answer was empty, which is a different problem from a cut one") {
                result shouldBe CompactionOutcome.SummaryRefused("empty")
            }
            then("nothing is cleared behind an empty record") {
                state.messages.size shouldBe 4
                state.summary shouldBe null
            }
        }
    }

    /**
     * A refusal in the middle of a fold must not undo the batches already proved, and the outcome
     * has to report the work that did land. This is the end-to-end version of the rule that a
     * failed batch only stops the fold: the first summary stands, only its turns are cleared, and
     * the refused batch is still there verbatim for the next attempt.
     */
    given("a fold whose first batch is accepted and whose second is refused") {
        val state = SessionState(
            "s", "m", null,
            mutableListOf(Msg("user", "A".repeat(2_000)), Msg("assistant", "B".repeat(2_000)))
        )
        var calls = 0
        val compactor = Compactor(
            // A budget this small floors the ceiling at 4k characters, which is what splits these
            // two turns into two batches instead of one.
            AppConfig(contextBudgetTokens = 100),
            summarize = { _, _ ->
                calls++
                if (calls == 1) Turn("first summary", null, null, "response.completed")
                else Turn(
                    "half a second summary", null, null, "response.incomplete",
                    incompleteReason = "max_output_tokens"
                )
            }
        )
        `when`("compacting with nothing kept verbatim") {
            val result = compactor.compact(state, keepLast = 0) {}
            then("two batches were attempted, so this is a mid-fold refusal and not a blocked start") {
                calls shouldBe 2
            }
            then("the work that landed is reported, and the refusal does not overwrite it") {
                result shouldBe CompactionOutcome.Compacted("first summary")
            }
            then("only the proved batch is cleared and the refused turn is kept whole") {
                state.summary shouldBe "first summary"
                state.messages.size shouldBe 1
                state.messages[0].content shouldBe "B".repeat(2_000)
            }
        }
    }

    given("a refusal to explain") {
        `when`("naming why each answer was refused") {
            then("an empty answer is reported as empty") {
                Compactor.refusalReason(Turn("  ", null, null, "response.completed"), 2_048) shouldBe "empty"
            }
            then("a truncation carries the backend's own reason and the bound that cut it") {
                Compactor.refusalReason(
                    Turn(
                        "partial", null, null, "response.incomplete",
                        incompleteReason = "max_output_tokens"
                    ),
                    2_048
                ) shouldBe "incomplete: max_output_tokens (2048)"
            }
            then("a missing completion event names whatever did arrive, and no bound it did not hit") {
                Compactor.refusalReason(Turn("text", null, null, "response.failed"), 512) shouldBe
                    "no response.completed, got response.failed"
            }
            then("a stream that closed silently says so, rather than naming an event that never came") {
                Compactor.refusalReason(Turn("text", null, null, null), 2_048) shouldBe
                    "no response.completed, got a stream that closed silently"
            }
        }
    }
})
