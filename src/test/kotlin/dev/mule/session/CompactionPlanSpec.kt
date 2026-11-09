package dev.mule.session

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class CompactionPlanSpec : BehaviorSpec({

    given("more turns than the keep-last window") {
        `when`("planning the split") {
            then("the last keepLast turns stay verbatim") {
                CompactionPlan.splitAt(6, 4) shouldBe 2
            }
        }
    }

    given("exactly as many turns as the keep-last window") {
        `when`("planning the split") {
            then("nothing is folded, so a summary is not paid for twice") {
                CompactionPlan.splitAt(4, 4) shouldBe null
            }
        }
    }

    given("fewer turns than the keep-last window") {
        `when`("planning the split") {
            then("nothing is folded") {
                CompactionPlan.splitAt(2, 4) shouldBe null
            }
        }
    }

    given("an older slice plus a summary from a previous compaction") {
        val source = CompactionPlan.source(
            "decisions from round one",
            listOf(Msg("user", "folded question"), Msg("assistant", "folded answer"))
        )

        `when`("building the text to summarise") {
            then("every folded turn is present") {
                source.contains("folded question") shouldBe true
                source.contains("folded answer") shouldBe true
            }
            then("the previous summary rides along, or it is lost on the second compaction") {
                source.contains("decisions from round one") shouldBe true
            }
        }
    }

    given("no previous summary") {
        `when`("building the text to summarise") {
            then("no placeholder text leaks into the prompt") {
                CompactionPlan.source(null, listOf(Msg("user", "q"))).contains("null") shouldBe false
            }
        }
    }

    /**
     * The fold has to work on a conversation that no longer fits one call, so the batch it picks
     * is the difference between compacting rescuing such a session and failing on the same
     * context error. These pin that a batch fits, that it never comes back empty, and that what
     * does not fit is handed to the call after it rather than lost.
     */
    given("four folded turns of a hundred characters each and a ceiling of three hundred") {
        val older = (1..4).map { Msg("user", "t$it-" + "x".repeat(96)) }
        `when`("picking the next batch") {
            val (batch, rest) = CompactionPlan.foldBatch(older, summaryChars = 0, maxChars = 300)
            then("only the turns that fit are taken, oldest first") {
                batch.map { it.content } shouldBe listOf(older[0].content, older[1].content)
            }
            then("the rest waits for the call after it, so nothing is silently skipped") {
                rest.map { it.content } shouldBe listOf(older[2].content, older[3].content)
            }
        }
    }

    given("a summary that already fills most of the ceiling") {
        val older = listOf(Msg("user", "x".repeat(5_000)), Msg("assistant", "y".repeat(20)))
        `when`("picking the next batch") {
            val (batch, rest) = CompactionPlan.foldBatch(older, summaryChars = 47_000, maxChars = 48_000)
            then("the fold still takes one turn, so a fold can always stall-proof itself") {
                batch.size shouldBe 1
                rest.size shouldBe 1
            }
        }
    }

    given("fewer turns than the ceiling allows") {
        `when`("picking the next batch") {
            val (batch, rest) = CompactionPlan.foldBatch(
                listOf(Msg("user", "small"), Msg("assistant", "answer")),
                summaryChars = 0, maxChars = 48_000
            )
            then("everything goes in one batch and the fold is done") {
                batch.size shouldBe 2
                rest shouldBe emptyList()
            }
        }
    }

    /**
     * A carried summary is not the only thing the wrapper spends: source also writes the
     * "(previous summary) " prefix and a blank line around it. A batch sized to the bare summary
     * length overflows the ceiling by those wrapper characters once the batch is poured in, and a
     * fold that then had to stop mid-batch would leave a turn half-read. This pins that the wrapper
     * is reserved, so a fitting batch stays completely inside the ceiling.
     */
    given("a carried summary and turns that fit only when the wrapper is reserved") {
        val older = (1..6).map { Msg("user", "x".repeat(987)) }
        `when`("picking the batch next to a thousand-character summary under a five thousand ceiling") {
            val (batch, rest) = CompactionPlan.foldBatch(older, summaryChars = 1_000, maxChars = 5_000)
            then("the batch plus the wrapped summary stays inside the ceiling") {
                CompactionPlan.source("s".repeat(1_000), batch).length shouldBe 4_006
                (CompactionPlan.source("s".repeat(1_000), batch).length <= 5_000) shouldBe true
            }
            then("one turn fewer than an unreserved batch would take, so nothing spills over") {
                batch.size shouldBe 3
                rest.size shouldBe 3
            }
        }
    }

    /**
     * The shape of a session that actually gets stranded: one enormous tool observation as the
     * oldest turn, in a conversation already past the backend's window. foldBatch takes it -- a
     * fold that refuses to take a turn can never finish -- and the caller keeps such an oversized
     * turn verbatim rather than sending it cut, because the cut tail would never reach the model
     * while the whole turn got cleared once its summary was accepted. This pins the turn arriving
     * whole, held back for a call that can carry it, never halving the fold.
     */
    given("a stranded session whose oldest turn is a 400k character tool observation") {
        val huge = Msg("tool", "O".repeat(400_000))
        `when`("picking the batch under the ceiling one call may carry") {
            val (batch, rest) = CompactionPlan.foldBatch(listOf(huge), summaryChars = 0, maxChars = 48_000)
            then("the fold takes it, so the fold cannot stall on an oversized turn") {
                batch.size shouldBe 1
                rest shouldBe emptyList()
            }
        }
    }
})
