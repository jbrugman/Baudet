package dev.baudet.app

import dev.baudet.api.BackendException
import dev.baudet.api.CancellationToken
import dev.baudet.api.ModelGateway
import dev.baudet.api.Turn
import dev.baudet.config.ToolMode
import dev.baudet.i18n.Language
import dev.baudet.i18n.Texts
import dev.baudet.session.Msg
import dev.baudet.store.H2Store
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files

/**
 * The run, stop and persist decisions that make up a turn used to hide three inline
 * ResponsesClient call sites, so a fix could pass its own unit test while the real flow
 * took a different path -- exactly how an earlier stop fix was tested against the wrong
 * exception. Wiring the transport behind AppState.gatewayFactory lets these flow tests drive
 * that decision end to end through a plain turn against a scripted [FakeGateway], with no
 * backend and no GUI thread. Each case fixes one guarantee about what the session holds after
 * a turn, on the store the same AppState writes to.
 *
 * The fixture builds a fresh store, AppState and gateway on every call, so a leaf observes only
 * the turn it sends -- Kotest re-runs each container once per leaf, and shared mutable state
 * would leak from one leaf into the next.
 */
class AppStateTurnFlowSpec : BehaviorSpec({

    afterEach { Texts.language = Language.EN }
    beforeEach { Texts.language = Language.EN }

    given("an empty conversation and a transport that answers") {
        val f = fixture { gw ->
            gw.actions.add { _, on -> on("ok"); Turn("ok", "resp-1", null, "response.completed") }
        }
        `when`("one prompt is sent") {
            val turn = f.state.send("hi", onDelta = {})
            then("the prompt and the answer are both kept in order") {
                f.state.session.messages.map { it.content } shouldBe listOf("hi", "ok")
            }
            then("the answer is persisted to the store") {
                f.store.messages(f.state.session.sessionId).map { it.content } shouldBe listOf("hi", "ok")
            }
            then("the server response id is remembered for the next turn") {
                f.state.session.previousResponseId shouldBe "resp-1"
                turn.completed shouldBe true
            }
            then("the transport was asked exactly once") {
                f.gateway.calls.size shouldBe 1
            }
        }
    }

    given("a turn deliberately stopped mid-stream") {
        // A Stop closes the live socket, so the caller sees a closed-stream IOException rather
        // than a clean cancellation. Keeping the half answer must key off the stop, not the type.
        val f = fixture { gw ->
            gw.actions.add { token, on ->
                on("half")
                token.cancel()
                throw UncheckedIOException(IOException("closed"))
            }
        }
        `when`("the send unwinds on the closed stream") {
            val thrown = runCatching { f.state.send("write a long essay", onDelta = {}) }.exceptionOrNull()
            then("the closed stream still reaches the caller") {
                (thrown is UncheckedIOException) shouldBe true
            }
            then("the prompt and the partial answer are kept so the next turn can steer") {
                f.state.session.messages.map { it.content } shouldBe listOf("write a long essay", "half")
            }
            then("the partial is persisted before the failure") {
                f.store.messages(f.state.session.sessionId).map { it.content } shouldBe
                    listOf("write a long essay", "half")
            }
        }
    }

    given("a stop before any text arrived") {
        val f = fixture { gw ->
            gw.actions.add { token, _ ->
                token.cancel()
                throw UncheckedIOException(IOException("closed"))
            }
        }
        `when`("the empty partial would have nothing to keep") {
            runCatching { f.state.send("hello", onDelta = {}) }
            then("the unanswered prompt is dropped, leaving the conversation clean") {
                f.state.session.messages.map { it.content } shouldBe emptyList()
            }
        }
    }

    given("a backend that refuses the prompt for being too long") {
        val f = fixture { gw ->
            gw.actions.add { _, _ ->
                throw BackendException(200, "Prompt is too long: 130779 tokens > 8000 maximum context length")
            }
            gw.actions.add { _, on -> on("ok"); Turn("ok", "resp-2", null, "response.completed") }
        }
        `when`("one prompt is sent under a trimming strategy") {
            val turn = f.state.send("hi", onDelta = {})
            then("the window the refusal named is learned") {
                f.state.config.learnedContextWindow shouldBe 8000
            }
            then("the same turn is retried and succeeds on the second call") {
                f.gateway.calls.size shouldBe 2
                turn.completed shouldBe true
                f.state.session.messages.map { it.content } shouldBe listOf("hi", "ok")
            }
        }
    }

    given("a backend that fails for an unrelated reason") {
        val f = fixture { gw ->
            gw.actions.add { _, _ -> throw BackendException(500, "internal server error") }
        }
        `when`("one prompt is sent") {
            val thrown = runCatching { f.state.send("hi", onDelta = {}) }.exceptionOrNull()
            then("the failure reaches the caller") {
                (thrown is BackendException) shouldBe true
            }
            then("the unanswered prompt is dropped, since nothing was appended") {
                f.state.session.messages.map { it.content } shouldBe emptyList()
            }
        }
    }
})

/** A fresh store plus the AppState bound to it, and the scripted gateway installed on that state. */
private class Fixture(val store: H2Store, val state: AppState, val gateway: FakeGateway)

/** Builds a clean turn flow: the optional [script] queues the gateway actions before the AppState binds it. */
private fun fixture(script: (FakeGateway) -> Unit = {}): Fixture {
    val store = H2Store(Files.createTempDirectory("baudet-flow").resolve("db"))
    val gateway = FakeGateway()
    script(gateway)
    // ToolMode.Off keeps every turn on the plain path, so the flow under test is the one-shot
    // reply rather than the tool loop; a non-blank model stands in for a real selection.
    val state = AppState(store).apply {
        config = config.copy(model = "flow-model", toolMode = ToolMode.Off)
        gatewayFactory = { gateway }
    }
    return Fixture(store, state, gateway)
}

private data class TurnCall(
    val system: String,
    val history: List<Msg>,
    val userText: String,
    val previousResponseId: String?,
    val tools: String?
)

/** A scripted [ModelGateway]: it forwards nothing, records the turns it was handed, and runs one
 *  queued action per call so a test can stream text, cancel the token, throw, or combine them. */
private class FakeGateway : ModelGateway {

    val calls = mutableListOf<TurnCall>()
    val actions = mutableListOf<(CancellationToken, (String) -> Unit) -> Turn>()
    var completes = 0

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
        calls.add(TurnCall(system, history.toList(), userText, previousResponseId, tools))
        val action = actions.removeFirstOrNull()
            ?: { _, on -> on("default"); Turn("default", "resp-default", null, "response.completed") }
        return action(token, onDelta)
    }

    override fun completeOnce(system: String, userText: String, maxTokens: Int, onDelta: (String) -> Unit): Turn {
        completes++
        onDelta("summary")
        return Turn("summary", null, null, "response.completed")
    }
}
