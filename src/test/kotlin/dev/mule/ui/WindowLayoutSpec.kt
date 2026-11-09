package dev.mule.ui

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class WindowLayoutSpec : BehaviorSpec({

    given("a rebuild of the panels") {
        `when`("a live divider width is carried over") {
            then("the live width wins over the stored one") {
                WindowLayout.resolveDivider(previous = 600, persisted = 700, fallback = 720) shouldBe 600
            }
            then("a collapsed live width stays collapsed rather than reopening") {
                WindowLayout.resolveDivider(previous = 0, persisted = 700, fallback = 720) shouldBe 0
            }
        }
        `when`("nothing is live, as on the first build") {
            then("a stored positive width is restored") {
                WindowLayout.resolveDivider(previous = null, persisted = 700, fallback = 720) shouldBe 700
            }
            then("an unset stored width of zero falls back to the default") {
                WindowLayout.resolveDivider(previous = null, persisted = 0, fallback = 720) shouldBe 720
            }
        }
    }
})
