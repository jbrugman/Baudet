package dev.baudet.tool

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The patch arithmetic is the whole contract of a file edit and it runs before the
 * disk is ever touched, so it is pinned here as pure value-in/value-out: an exact
 * single hit applies, a miss or an ambiguous match rejects without applying, and
 * replace_all is the caller's explicit opt-in to change every occurrence.
 */
class PatchApplierSpec : BehaviorSpec({

    given("a file with one clear occurrence") {
        val content = "alpha\nbeta\ngamma\n"
        `when`("replacing that exact line") {
            val out = PatchApplier.apply(content, listOf(Edit("beta", "BETA")))
            then("the single hit is applied in place") {
                (out is PatchOutcome.Applied) shouldBe true
                (out as PatchOutcome.Applied).content shouldBe "alpha\nBETA\ngamma\n"
            }
        }
        `when`("targeting text that is not there") {
            val out = PatchApplier.apply(content, listOf(Edit("delta", "D")))
            then("the edit is rejected as not found") {
                (out is PatchOutcome.Rejected) shouldBe true
                (out as PatchOutcome.Rejected).reason.contains("not found") shouldBe true
            }
        }
    }

    given("a line that appears twice") {
        val content = "x\nx\n"
        `when`("replacing without replace_all") {
            val out = PatchApplier.apply(content, listOf(Edit("x", "y")))
            then("the ambiguity is refused, naming the count") {
                (out is PatchOutcome.Rejected) shouldBe true
                (out as PatchOutcome.Rejected).reason.contains("matches 2 times") shouldBe true
            }
        }
        `when`("replacing with replace_all") {
            val out = PatchApplier.apply(content, listOf(Edit("x", "y", replaceAll = true)))
            then("every occurrence changes") {
                (out as PatchOutcome.Applied).content shouldBe "y\ny\n"
            }
        }
    }

    given("several edits in order") {
        `when`("each applies to the previous result") {
            val out = PatchApplier.apply("a\nb\n", listOf(Edit("a", "A"), Edit("b", "B")))
            then("they fold in sequence") {
                (out as PatchOutcome.Applied).content shouldBe "A\nB\n"
            }
        }
    }

    given("an empty edit list") {
        `when`("applying it") {
            val out = PatchApplier.apply("anything", emptyList())
            then("it is refused rather than a silent no-op") {
                (out is PatchOutcome.Rejected) shouldBe true
            }
        }
    }
})
