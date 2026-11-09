package dev.baudet.config

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The two applicability properties are the single source of truth for which Settings controls the
 * current selection leaves live. They must agree exactly with what the request builder reads, or
 * the form would grey out a control that still bites, or leave live one that nothing reads.
 */
class ContextStrategySpec : BehaviorSpec({

    given("a context strategy") {
        `when`("it is Stop at limit") {
            val s = ContextStrategy.StopAtLimit
            then("it alone can carry a server-side session") {
                s.supportsServerSession shouldBe true
            }
            then("it is not bounded by a recent-turn count") {
                s.trimsByTurnCount shouldBe false
            }
        }
        `when`("it is keep last turns") {
            val s = ContextStrategy.RecentMessages
            then("it alone trims by a recent-turn count") {
                s.trimsByTurnCount shouldBe true
            }
            then("it cannot carry a server-side session") {
                s.supportsServerSession shouldBe false
            }
        }
        `when`("it is a rolling window") {
            val s = ContextStrategy.RollingWindow
            then("neither control applies") {
                s.supportsServerSession shouldBe false
                s.trimsByTurnCount shouldBe false
            }
        }
    }
})
