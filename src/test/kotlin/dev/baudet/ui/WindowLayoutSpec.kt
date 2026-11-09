package dev.baudet.ui

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

    given("a remembered dialog size") {
        `when`("nothing was remembered") {
            then("the fallback opens the dialog") {
                WindowLayout.resolveDialogSize(remembered = 0, fallback = 560) shouldBe 560
            }
        }
        `when`("a size was remembered") {
            then("it is restored verbatim") {
                WindowLayout.resolveDialogSize(remembered = 800, fallback = 560) shouldBe 800
            }
        }
        `when`("a just-closed dialog is measured") {
            then("a size at or above the minimum is worth remembering") {
                WindowLayout.shouldRememberDialogSize(width = 640, height = 420, min = 200) shouldBe true
            }
            then("a squashed dialog is never remembered as the new default") {
                WindowLayout.shouldRememberDialogSize(width = 120, height = 420, min = 200) shouldBe false
                WindowLayout.shouldRememberDialogSize(width = 640, height = 90, min = 200) shouldBe false
            }
        }
    }
})
