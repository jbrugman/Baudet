package dev.baudet.app

import dev.baudet.api.CancellationToken
import dev.baudet.api.ModelGateway
import dev.baudet.api.Turn
import dev.baudet.config.ToolMode
import dev.baudet.session.Msg
import dev.baudet.store.H2Store
import dev.baudet.thread.AgentMessageItem
import dev.baudet.thread.ItemKind
import dev.baudet.thread.ReasoningItem
import dev.baudet.thread.UserMessageItem
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * A turn's rich rows must outlive it: they are what makes a reload look like the conversation the
 * reader had rather than a flat transcript. These cases drive a real turn through [AppState.send]
 * against a scripted gateway -- no GUI thread -- and then reopen the same store in a fresh
 * [AppState] to assert exactly the guarantee the feature is for: the cards a turn produced are
 * back, a reasoning row is deliberately not, and the finished answer text is intact.
 */
class AppStateItemSpec : BehaviorSpec({

    given("a plain turn that streams reasoning and an answer") {
        val dir = Files.createTempDirectory("baudet-item").resolve("db")
        val store = H2Store(dir)
        val state = AppState(store).apply {
            config = config.copy(model = "m", toolMode = ToolMode.Off)
            gatewayFactory = { ItemGateway(answer = "here you go", reasoning = "let me think") }
        }
        `when`("one prompt is sent and persisted") {
            state.send("hi", onDelta = {})
            then("the session captured the prompt and the answer as rows, in order") {
                state.session.items.map { it.kind } shouldBe listOf(
                    ItemKind.UserMessage,
                    ItemKind.AgentMessage
                )
            }
            then("reasoning is never captured into the rows") {
                state.session.items.any { it is ReasoningItem } shouldBe false
            }
            then("a fresh AppState over the same store reloads the same rows") {
                val reloaded = AppState(store)
                reloaded.session.items.map { it.kind } shouldBe listOf(
                    ItemKind.UserMessage,
                    ItemKind.AgentMessage
                )
            }
            then("the reloaded answer carries its finished text") {
                val answer = AppState(store).session.items.filterIsInstance<AgentMessageItem>().single()
                answer.text shouldBe "here you go"
            }
        }
    }
    given("a conversation longer than one page, reopened") {
        val dir = Files.createTempDirectory("baudet-page").resolve("db")
        val store = H2Store(dir)
        val id = "s-page"
        store.rewriteItems(id, (0 until 250).map { UserMessageItem("i$it", "row $it") })
        val state = AppState(store)
        state.loadSession(id)
        `when`("it reopens on just its newest page") {
            then("one page is held while the store keeps the older rows") {
                state.session.items.size shouldBe 200
                state.session.hasMoreOlder shouldBe true
                store.items(id).size shouldBe 250
            }
            then("a write reloads the older rows first, so none are lost") {
                state.persist()
                store.items(id).size shouldBe 250
                state.session.items.size shouldBe 250
                state.session.hasMoreOlder shouldBe false
            }
        }
    }
})

/** A scripted [ModelGateway] for the plain path: it streams reasoning then an answer in one turn. */
private class ItemGateway(private val answer: String, private val reasoning: String) : ModelGateway {

    override fun turn(
        system: String,
        history: List<Msg>,
        userText: String,
        previousResponseId: String?,
        tools: String?,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit,
        token: CancellationToken,
        image: String?
    ): Turn {
        onReasoning(reasoning)
        onDelta(answer)
        return Turn(answer, "resp-1", null, "response.completed")
    }

    override fun completeOnce(
        system: String,
        userText: String,
        maxTokens: Int,
        onDelta: (String) -> Unit
    ): Turn {
        onDelta("summary")
        return Turn("summary", null, null, "response.completed")
    }
}
