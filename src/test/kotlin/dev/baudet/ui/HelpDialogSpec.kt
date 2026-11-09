package dev.baudet.ui

import dev.baudet.i18n.Key
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * HelpDialog opens a modal window, which a test cannot reasonably drive, so only the tab-to-body
 * mapping -- the single decision that can be wrong -- is covered here. The menu hands this the raw
 * JTabbedPane index, so a shift in tab order must keep pointing Help at the matching body.
 */
class HelpDialogSpec : BehaviorSpec({

    given("the tab the user is on") {
        `when`("the Models tab is selected") {
            then("Help opens the Models body") {
                HelpDialog.bodyKeyFor(1) shouldBe Key.HelpBodyModels
            }
        }
        `when`("the Settings tab is selected") {
            then("Help opens the Settings body") {
                HelpDialog.bodyKeyFor(2) shouldBe Key.HelpBodySettings
            }
        }
        `when`("the Chat tab, or any index outside the known tabs") {
            then("Help falls back to the Chat body") {
                HelpDialog.bodyKeyFor(0) shouldBe Key.HelpBodyChat
                HelpDialog.bodyKeyFor(-1) shouldBe Key.HelpBodyChat
            }
        }
    }
})
