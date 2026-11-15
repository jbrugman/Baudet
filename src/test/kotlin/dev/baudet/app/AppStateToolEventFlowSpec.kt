package dev.baudet.app

import dev.baudet.api.CancellationToken
import dev.baudet.api.ModelGateway
import dev.baudet.api.Turn
import dev.baudet.api.Usage
import dev.baudet.config.ToolMode
import dev.baudet.i18n.Language
import dev.baudet.i18n.Texts
import dev.baudet.project.FolderPermission
import dev.baudet.project.Permissions
import dev.baudet.session.Msg
import dev.baudet.store.H2Store
import dev.baudet.thread.AgentMessageItem
import dev.baudet.thread.FileChangeItem
import dev.baudet.thread.ItemCompleted
import dev.baudet.thread.ItemStarted
import dev.baudet.thread.ItemStatus
import dev.baudet.thread.TurnCompleted
import dev.baudet.thread.TurnDiffUpdated
import dev.baudet.thread.TokenUsageUpdated
import dev.baudet.thread.ThreadEvent
import dev.baudet.thread.UserMessageItem
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files

/**
 * The tool turn as rows, end to end: the prompt, the model's step, the write it asked for with
 * the diff that was really produced, the answer after the observation went back, and finally the
 * accounting and the turn's accumulated diff.
 *
 * This is the gap the flat transcript left: [dev.baudet.ui.ChatPanel] used to receive only text
 * callbacks, so a local model's tool call was invisible to the reader -- and for a local model
 * that is precisely the part worth showing, because the reader is the one whose hardware ran it.
 * The events are asserted as an ordered sequence rather than as membership, because a renderer
 * that receives a completion before its start cannot draw the row at all, and a card whose exit
 * code or diff is invented is the dishonesty this app exists to prevent: the numbers asserted
 * here are counted from the diff the edit tool really wrote.
 */
class AppStateToolEventFlowSpec : BehaviorSpec({

    beforeEach { Texts.language = Language.EN }
    afterEach { Texts.language = Language.EN }

    given("a tool-active conversation where the model edits a file and then answers") {
        val f = eventFixture { gw ->
            gw.actions.add { _, on ->
                on("writing the change")
                Turn(
                    "writing the change\n```baudet-tool\n" +
                        "{\"tool\":\"edit_file\",\"arguments\":{\"file\":\"a.txt\",\"edits\":[" +
                        "{\"old_text\":\"one\",\"new_text\":\"two\"}]}}\n```",
                    "resp-1", null, "response.completed"
                )
            }
            gw.actions.add { _, on ->
                on("all ")
                on("done")
                Turn("all done", "resp-2", Usage(11, 4, 15), "response.completed")
            }
        }
        `when`("the tool turn runs to its end") {
            val events = mutableListOf<ThreadEvent>()
            f.state.send("change it", onDelta = {}, onEvent = { events += it })

            then("every event is scoped to this conversation and this turn") {
                val sid = f.state.session.sessionId
                events.map { it.threadId }.distinct() shouldBe listOf(sid)
                events.map { it.turnId }.distinct() shouldBe listOf("$sid-t1")
            }
            then("the turn opens, closes every row it opened, and ends with the accounting and the diff") {
                events.map { it.wireMethod } shouldBe listOf(
                    "turn/started",
                    "item/started", "item/completed",
                    "item/started", "item/agentMessage/delta", "item/completed",
                    "item/started", "item/completed",
                    "item/started", "item/agentMessage/delta", "item/agentMessage/delta", "item/completed",
                    "thread/tokenUsage/updated", "turn/diff/updated", "turn/completed"
                )
            }
            then("the prompt is a row of its own, so a transcript rebuilds from rows alone") {
                val prompt = events.filterIsInstance<ItemStarted>().first().item as UserMessageItem
                prompt.text shouldBe "change it"
            }
            then("the write reports the file, its real diff and the counts taken from it") {
                val change = events.filterIsInstance<ItemCompleted>()
                    .single { it.item is FileChangeItem }.item as FileChangeItem
                change.path shouldBe "a.txt"
                change.status shouldBe ItemStatus.Completed
                change.diff shouldContain "two"
                change.additions shouldBe 1
                change.deletions shouldBe 1
            }
            then("the change is really on disk, so the card does not promise more than happened") {
                Files.readString(f.folder.resolve("a.txt")) shouldContain "two"
            }
            then("the turn offers one accumulated diff rather than making the reader add up cards") {
                val diff = events.filterIsInstance<TurnDiffUpdated>().single()
                diff.files.map { it.path } shouldBe listOf("a.txt")
                diff.additions shouldBe 1
                diff.deletions shouldBe 1
            }
            then("the accounting leaves with the turn and carries the backend's own numbers") {
                events.filterIsInstance<TokenUsageUpdated>().single().usage shouldBe Usage(11, 4, 15)
            }
            then("the turn closes naming the response that answered") {
                val done = events.filterIsInstance<TurnCompleted>().single()
                done.responseId shouldBe "resp-2"
                done.incompleteReason shouldBe null
            }
            then("no row is left in progress") {
                events.filterIsInstance<ItemCompleted>().map { it.item.status }
                    .contains(ItemStatus.InProgress) shouldBe false
            }
        }
    }

    given("a tool-active conversation whose tool call fails") {
        val f = eventFixture { gw ->
            gw.actions.add { _, on ->
                Turn(
                    "```baudet-tool\n{\"tool\":\"edit_file\",\"arguments\":{\"file\":\"a.txt\",\"edits\":[" +
                        "{\"old_text\":\"not in the file\",\"new_text\":\"x\"}]}}\n```",
                    "resp-1", null, "response.completed"
                )
            }
            gw.actions.add { _, on ->
                Turn("I could not match that", "resp-2", Usage(3, 2, 5), "response.completed")
            }
        }
        `when`("the rejected write is reported") {
            val events = mutableListOf<ThreadEvent>()
            f.state.send("change it", onDelta = {}, onEvent = { events += it })

            then("the card is failed rather than quietly dropped, so the reader sees the attempt") {
                val change = events.filterIsInstance<ItemCompleted>()
                    .single { it.item is FileChangeItem }.item as FileChangeItem
                change.status shouldBe ItemStatus.Failed
            }
            then("a turn that wrote nothing offers no diff to review") {
                events.filterIsInstance<TurnDiffUpdated>() shouldBe emptyList()
            }
            then("the turn still closes, because the model did answer afterwards") {
                events.filterIsInstance<TurnCompleted>().single().incompleteReason shouldBe null
                events.last().wireMethod shouldBe "turn/completed"
            }
        }
    }

    given("a tool-active conversation stopped while the model is still answering") {
        val f = eventFixture { gw ->
            gw.actions.add { token, on ->
                on("half an")
                token.cancel()
                throw UncheckedIOException(IOException("closed"))
            }
        }
        `when`("the stop unwinds the turn") {
            val events = mutableListOf<ThreadEvent>()
            val thrown = runCatching {
                f.state.send("change it", onDelta = {}, onEvent = { events += it })
            }.exceptionOrNull()

            then("the failure still reaches the caller") {
                (thrown is UncheckedIOException) shouldBe true
            }
            then("the half answer is kept and marked interrupted, so the next instruction can steer it") {
                val partial = events.filterIsInstance<ItemCompleted>()
                    .single { it.item is AgentMessageItem }.item as AgentMessageItem
                partial.text shouldBe "half an"
                partial.status shouldBe ItemStatus.Interrupted
            }
            then("the turn says it was interrupted rather than failed, which is not the same event") {
                events.filterIsInstance<TurnCompleted>().single().incompleteReason shouldBe "interrupted"
            }
            then("no row is left in progress") {
                events.map { it.wireMethod }.last() shouldBe "turn/completed"
            }
        }
    }
})

/** A fresh store, the AppState bound to it, the scripted gateway installed on that state, and the project folder. */
private class EventFixture(
    val store: H2Store,
    val state: AppState,
    val gateway: EventGateway,
    val folder: java.nio.file.Path
)

/**
 * A clean tool path with something to edit: a linked folder carrying the Read and Write rights, so
 * [AppState.runToolTurn] is chosen and the edit tool has a real file to patch. Every call builds a
 * fresh store and state, so a leaf observes only the turn it sends -- Kotest re-runs each container
 * once per leaf. [ToolMode.Auto] answers the approval for the write, which is not what is under
 * test here; the rows are.
 */
private fun eventFixture(script: (EventGateway) -> Unit): EventFixture {
    val store = H2Store(Files.createTempDirectory("baudet-evflow").resolve("db"))
    val gateway = EventGateway()
    script(gateway)
    val state = AppState(store).apply {
        config = config.copy(model = "flow-model", toolMode = ToolMode.Auto)
        gatewayFactory = { gateway }
    }
    val project = state.addProject("P")
    val folder = Files.createTempDirectory("baudet-evfolder")
    Files.writeString(folder.resolve("a.txt"), "one\n")
    state.linkFolder(
        project.id,
        folder.toString(),
        Permissions.encode(setOf(FolderPermission.Read, FolderPermission.Write))
    )
    state.newSession(project.id)
    return EventFixture(store, state, gateway, folder)
}

/**
 * A scripted [ModelGateway] for the row tests. One queued action per model turn, so a test decides
 * exactly what the model said and whether it streamed, cancelled or both -- and because the action
 * returns the [Turn] the extractor reads, a fenced call here really drives a real tool.
 */
private class EventGateway : ModelGateway {

    val actions = mutableListOf<(CancellationToken, (String) -> Unit) -> Turn>()

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
        val action = actions.removeFirstOrNull()
            ?: { _, on -> on("default"); Turn("default", "resp-default", null, "response.completed") }
        return action(token, onDelta)
    }

    override fun completeOnce(system: String, userText: String, maxTokens: Int, onDelta: (String) -> Unit): Turn {
        onDelta("summary")
        return Turn("summary", null, null, "response.completed")
    }
}
