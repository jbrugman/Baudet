package dev.baudet.ui

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The dock has to stay subordinate to the conversation, and two rules decide that: when it is
 * allowed to take space at all, and when an arriving chunk may move the view. Both are pure, so
 * the guarantee is checked here rather than by watching a stream by hand.
 */
class ThinkingLayoutSpec : BehaviorSpec({

    given("reasoning that has just arrived") {
        `when`("nobody closed the dock during this turn") {
            then("the dock takes its row above the input") {
                ThinkingLayout.dockWanted(hasReasoning = true, collapsed = false) shouldBe true
            }
        }
        `when`("the reader closed it during this turn") {
            then("the remaining chunks of that turn do not reopen it") {
                ThinkingLayout.dockWanted(hasReasoning = true, collapsed = true) shouldBe false
            }
        }
    }

    given("a turn with no reasoning at all") {
        `when`("nothing was thought out loud") {
            then("the input keeps every row, so an empty dock never shows") {
                ThinkingLayout.dockWanted(hasReasoning = false, collapsed = false) shouldBe false
            }
        }
    }

    given("a scroll bar resting at the bottom") {
        `when`("the view is exactly at the tail") {
            then("an append is allowed to follow it") {
                ThinkingLayout.followsTail(value = 900, extent = 100, maximum = 1000, slack = 24) shouldBe true
            }
        }
        `when`("the view is within the slack of the tail") {
            then("a rounded or mid-insert position still follows") {
                ThinkingLayout.followsTail(value = 876, extent = 100, maximum = 1000, slack = 24) shouldBe true
            }
        }
    }

    given("a reader who scrolled up to reread") {
        `when`("the view sits well above the tail") {
            then("no append drags the view back down") {
                ThinkingLayout.followsTail(value = 100, extent = 100, maximum = 1000, slack = 24) shouldBe false
            }
        }
        `when`("the dock is empty and nothing overflows") {
            then("the bar counts as resting at the tail, so the first chunks do follow") {
                ThinkingLayout.followsTail(value = 0, extent = 100, maximum = 100, slack = 24) shouldBe true
            }
        }
    }
})
