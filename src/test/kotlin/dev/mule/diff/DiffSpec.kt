package dev.mule.diff

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The preview a reviewer reads before a write is allowed: a wrong diff is a wrong
 * approval, so the line alignment and the unified text it becomes are pinned here,
 * apart from any UI and the disk.
 */
class DiffSpec : BehaviorSpec({

    given("a one-line change between two texts") {
        val ops = LineDiff.diff("a\nb\nc", "a\nX\nc")
        `when`("reading the ops") {
            then("the changed line is a remove followed by an add, the rest context") {
                ops.map {
                    when (it) {
                        is LineDiff.Op.Context -> " " + it.text
                        is LineDiff.Op.Add -> "+" + it.text
                        is LineDiff.Op.Remove -> "-" + it.text
                    }
                } shouldBe listOf(" a", "-b", "+X", " c")
            }
        }
        `when`("rendering it as a unified diff") {
            val text = UnifiedDiff.render("a/f", "b/f", ops)
            then("it carries the header and the +/- body") {
                text.startsWith("--- a/f\n+++ b/f\n") shouldBe true
                text.contains("-b\n") shouldBe true
                text.contains("+X\n") shouldBe true
            }
        }
    }

    given("an unchanged text") {
        `when`("diffing it with itself") {
            then("every line is context") {
                LineDiff.diff("a\nb", "a\nb").all { it is LineDiff.Op.Context } shouldBe true
            }
        }
    }
})
