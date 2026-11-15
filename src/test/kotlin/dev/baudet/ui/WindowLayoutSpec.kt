package dev.baudet.ui

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.awt.Rectangle

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

    given("a composer that must keep its height") {
        `when`("placing the divider for a composer height") {
            then("the transcript keeps the rest of the split") {
                WindowLayout.dividerForHeight(splitHeight = 600, componentHeight = 120, dividerSize = 10) shouldBe 470
            }
            then("a composer taller than the split clamps the divider to the top") {
                WindowLayout.dividerForHeight(splitHeight = 100, componentHeight = 400, dividerSize = 10) shouldBe 0
            }
        }
        `when`("reading a released drag back as a height") {
            then("the height is the inverse of the placement") {
                WindowLayout.componentHeightForDivider(splitHeight = 600, dividerLocation = 470, dividerSize = 10) shouldBe 120
            }
            then("a drag past the bottom clamps to zero") {
                WindowLayout.componentHeightForDivider(splitHeight = 100, dividerLocation = 200, dividerSize = 10) shouldBe 0
            }
        }
    }

    /**
     * A remembered window position is only worth restoring if the window can still be moved from
     * it, which means its top-left corner -- where the title bar starts -- has to sit inside a real
     * screen. The union of all screens cannot express this: it has no gaps and grazes at its edges,
     * which is how a window ended up with its bar off-screen and no way left to drag it back.
     */
    given("a two-screen desk, the second screen starting at x=2100 so x=2000 is dead space") {
        val screens = listOf(Rectangle(0, 0, 1920, 1080), Rectangle(2100, 0, 1920, 1080))
        `when`("restoring a remembered origin") {
            then("a corner inside a screen is restored") {
                WindowLayout.rememberedOriginIsReachable(400, 200, screens) shouldBe true
            }
            then("a corner in the gap between the screens is not") {
                WindowLayout.rememberedOriginIsReachable(2000, 500, screens) shouldBe false
            }
            then("that gap is exactly what the union of the screens accepted, which is the bug") {
                Rectangle(0, 0, 4020, 1080).intersects(Rectangle(2000, 500, 560, 360)) shouldBe true
            }
            then("a corner grazing the far edge by nothing is not, since contains() ends one short") {
                WindowLayout.rememberedOriginIsReachable(1920, 500, screens) shouldBe false
            }
            then("a corner below the screen after a resolution change is not") {
                WindowLayout.rememberedOriginIsReachable(400, 1080, screens) shouldBe false
            }
            then("the very corner of the primary screen is") {
                WindowLayout.rememberedOriginIsReachable(0, 0, screens) shouldBe true
            }
        }
    }

    given("a secondary screen placed to the left, at negative x") {
        val screens = listOf(Rectangle(0, 0, 1920, 1080), Rectangle(-1600, 0, 1600, 1080))
        `when`("restoring a remembered origin from that screen") {
            then("it is restored, so the stricter rule does not strand a legitimate second monitor") {
                WindowLayout.rememberedOriginIsReachable(-1200, 300, screens) shouldBe true
            }
        }
    }

    given("no screen reporting a configuration") {
        `when`("restoring any remembered origin") {
            then("nothing counts as reachable, so the window centers instead of vanishing") {
                WindowLayout.rememberedOriginIsReachable(100, 100, emptyList()) shouldBe false
            }
        }
    }
})
