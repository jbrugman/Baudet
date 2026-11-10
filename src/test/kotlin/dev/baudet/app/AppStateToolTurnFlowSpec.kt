package dev.baudet.app

import dev.baudet.api.CancellationToken
import dev.baudet.api.ModelGateway
import dev.baudet.api.Turn
import dev.baudet.config.ToolMode
import dev.baudet.i18n.Language
import dev.baudet.i18n.Texts
import dev.baudet.project.FolderPermission
import dev.baudet.project.Permissions
import dev.baudet.session.Msg
import dev.baudet.store.H2Store
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files

/**
 * The same run, stop and persist guarantees [AppStateTurnFlowSpec] fixes for the plain reply,
 * now driven through the tool loop. A deliberate stop there used to lose both the prompt and any
 * streamed half answer on the tool path, because the loop only records an assistant message once
 * the model turn returns -- so cancelling before that moment left the session holding just the
 * prompt, which the caller then dropped. Wiring the transport behind [AppState.gatewayFactory]
 * against a project with the Write right routes the turn into [AppState] runToolTurn, and a
 * scripted [FakeToolGateway] can stream a partial and then cancel mid-first-request with no tool
 * ever running. The fixture builds a fresh store, AppState and gateway on every call, so a leaf
 * observes only the turn it sends -- Kotest re-runs each container once per leaf.
 */
class AppStateToolTurnFlowSpec : BehaviorSpec({

    afterEach { Texts.language = Language.EN }
    beforeEach { Texts.language = Language.EN }

    given("a tool-active conversation where the model streams text then the turn is stopped") {
        val f = toolFixture { gw ->
            gw.actions.add { token, on ->
                on("thinking out loud")
                token.cancel()
                throw UncheckedIOException(IOException("closed"))
            }
        }
        `when`("the send unwinds on the closed stream before any tool ran") {
            val thrown = runCatching { f.state.send("read a file", onDelta = {}) }.exceptionOrNull()
            then("the closed stream still reaches the caller") {
                (thrown is UncheckedIOException) shouldBe true
            }
            then("the prompt and the partial answer are kept so the next turn can steer") {
                f.state.session.messages.map { it.content } shouldBe listOf("read a file", "thinking out loud")
            }
            then("the partial is persisted before the failure") {
                f.store.messages(f.state.session.sessionId).map { it.content } shouldBe
                    listOf("read a file", "thinking out loud")
            }
            then("the stale server response id is dropped so the next turn replays the transcript") {
                f.state.session.previousResponseId shouldBe null
            }
        }
    }

    given("a tool-active conversation stopped before any text arrived") {
        val f = toolFixture { gw ->
            gw.actions.add { token, _ ->
                token.cancel()
                throw UncheckedIOException(IOException("closed"))
            }
        }
        `when`("the empty partial would have nothing to keep") {
            runCatching { f.state.send("read a file", onDelta = {}) }
            then("the unanswered prompt is dropped, matching the plain path") {
                f.state.session.messages.map { it.content } shouldBe emptyList()
            }
        }
    }
})

/** A fresh store plus the AppState bound to it, and the scripted tool gateway installed on that state. */
private class ToolFixture(val store: H2Store, val state: AppState, val gateway: FakeToolGateway)

/**
 * Builds a clean tool path: a project whose linked folder carries the Write right, so [AppState]
 * runToolTurn is chosen, with the scripted gateway bound before any turn is sent. The script queues
 * the gateway actions; the model streams or cancels before returning, so no tool call is extracted
 * and no approval is requested -- only the stop and persist decision under test runs.
 */
private fun toolFixture(script: (FakeToolGateway) -> Unit = {}): ToolFixture {
    val store = H2Store(Files.createTempDirectory("baudet-toolflow").resolve("db"))
    val gateway = FakeToolGateway()
    script(gateway)
    val state = AppState(store).apply {
        config = config.copy(model = "flow-model", toolMode = ToolMode.Auto)
        gatewayFactory = { gateway }
    }
    val project = state.addProject("P")
    val folder = Files.createTempDirectory("baudet-toolfolder")
    state.linkFolder(
        project.id,
        folder.toString(),
        Permissions.encode(setOf(FolderPermission.Read, FolderPermission.Write))
    )
    state.newSession(project.id)
    return ToolFixture(store, state, gateway)
}

/** A scripted [ModelGateway] for the tool path: it forwards nothing, runs one queued action per
 *  turn call so a test can stream text, cancel the token, throw, or combine them. */
private class FakeToolGateway : ModelGateway {

    val actions = mutableListOf<(CancellationToken, (String) -> Unit) -> Turn>()

    override fun turn(
        system: String,
        history: List<Msg>,
        userText: String,
        previousResponseId: String?,
        tools: String?,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit,
        token: CancellationToken
    ): Turn {
        val action = actions.removeFirstOrNull()
            ?: { _, on -> on("default"); Turn("default", "resp-default", null, "response.completed") }
        return action(token, onDelta)
    }

    override fun completeOnce(system: String, userText: String, maxTokens: Int, onDelta: (String) -> Unit): Turn {
        onDelta("summary")
        return Turn("summary", null, null, "response.completed")
    }
}
