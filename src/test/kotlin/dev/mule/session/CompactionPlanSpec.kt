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
})
