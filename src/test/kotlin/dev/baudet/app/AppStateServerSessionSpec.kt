package dev.baudet.app

import dev.baudet.config.ContextStrategy
import dev.baudet.store.H2Store
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * The live-session status must report a server chain only where a chain is actually used. The
 * trimming strategies always replay from the client, so even a stored response id with the toggle on
 * is inert beneath them and the status must stay honest about it. Only Stop at limit lets a chain
 * lead, and only with the toggle on and a stored id does a session truly ride the server.
 */
class AppStateServerSessionSpec : BehaviorSpec({

    fun freshState(): AppState =
        AppState(H2Store(Files.createTempDirectory("baudet-serversess").resolve("db")))

    given("a keep-last strategy with the toggle on and a stored response id") {
        val state = freshState()
        state.newSession()
        state.saveConfig(state.config.copy(
            contextStrategy = ContextStrategy.RecentMessages, useServerSession = true
        ))
        state.session.previousResponseId = "resp-1"
        `when`("the live-session status is read") {
            then("it reports no server chain, because the turn replays from the client") {
                state.serverSessionActive() shouldBe false
            }
        }
    }

    given("a rolling-window strategy with the toggle on and a stored response id") {
        val state = freshState()
        state.newSession()
        state.saveConfig(state.config.copy(
            contextStrategy = ContextStrategy.RollingWindow, useServerSession = true
        ))
        state.session.previousResponseId = "resp-1"
        `when`("the live-session status is read") {
            then("it reports no server chain") {
                state.serverSessionActive() shouldBe false
            }
        }
    }

    given("a Stop-at-limit strategy with the toggle on and a stored response id") {
        val state = freshState()
        state.newSession()
        state.saveConfig(state.config.copy(
            contextStrategy = ContextStrategy.StopAtLimit, useServerSession = true
        ))
        state.session.previousResponseId = "resp-1"
        `when`("the live-session status is read") {
            then("it reports a live server chain") {
                state.serverSessionActive() shouldBe true
            }
        }
    }

    given("a Stop-at-limit strategy with the toggle off and a stored response id") {
        val state = freshState()
        state.newSession()
        state.saveConfig(state.config.copy(
            contextStrategy = ContextStrategy.StopAtLimit, useServerSession = false
        ))
        state.session.previousResponseId = "resp-1"
        `when`("the live-session status is read") {
            then("it reports no server chain") {
                state.serverSessionActive() shouldBe false
            }
        }
    }
})
