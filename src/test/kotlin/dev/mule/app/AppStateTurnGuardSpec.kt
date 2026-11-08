package dev.mule.app

import dev.mule.store.H2Store
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * While a turn runs, the live session must not move out from under the worker that
 * captured it, or a reply could land in a conversation the user switched to mid-stream.
 * These pin the guard: every session switcher is a no-op while [AppState.turning] is set.
 */
class AppStateTurnGuardSpec : BehaviorSpec({

    fun freshState(): AppState =
        AppState(H2Store(Files.createTempDirectory("mule-guard").resolve("db")))

    given("a conversation open while a turn runs") {
        val state = freshState()
        val a = state.newSession().sessionId
        val b = state.newSession().sessionId
        state.turning = true
        `when`("starting a new conversation is attempted") {
            then("the live session does not move") {
                state.newSession().sessionId shouldBe b
            }
        }
        `when`("loading another conversation is attempted") {
            state.loadSession(a)
            then("the open conversation stays put") {
                state.session.sessionId shouldBe b
            }
        }
    }

    given("deleting projects and conversations during a running turn") {
        val state = freshState()
        val p = state.addProject("P")
        val openId = state.newSession(p.id).sessionId
        state.turning = true
        `when`("delete is attempted") {
            state.deleteSession(openId)
            state.deleteProject(p.id)
            then("nothing is deleted while the turn runs") {
                state.projects().map { it.id } shouldBe listOf(p.id)
                state.sessionsIn(p.id).map { it.id } shouldBe listOf(openId)
            }
        }
    }
})
