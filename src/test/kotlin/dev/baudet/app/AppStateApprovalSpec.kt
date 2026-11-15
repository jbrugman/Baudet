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
import dev.baudet.thread.ApprovalDecision
import dev.baudet.thread.ApprovalRequest
import dev.baudet.thread.ApprovalRequested
import dev.baudet.thread.ApprovalResolved
import dev.baudet.thread.FileChangeItem
import dev.baudet.thread.ItemCompleted
import dev.baudet.thread.ThreadEmitter
import dev.baudet.thread.ThreadEvent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.withClue
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The inline approval handshake: the worker parks on a request, the answer -- a click, or a Stop
 * -- releases it, and exactly one decision counts.
 *
 * This replaces the modal dialog, so the two behaviours that used to live in a blocking window
 * have to live here instead and be true: a Stop must not leave a worker parked forever, and a
 * late click landing after a Stop must not run a step the reader chose to abandon. The races are
 * tested head-on rather than assumed safe, because the whole point of a latch is that the answer
 * arrives from another thread.
 */
class AppStateApprovalSpec : BehaviorSpec({

    beforeEach { Texts.language = Language.EN }
    afterEach { Texts.language = Language.EN }

    fun awaitUntil(label: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        withClue(label) { cond() shouldBe true }
    }

    /** Start a worker that parks on [requestApproval] and hands back its decision, plus the events it published. */
    fun park(state: AppState, request: ApprovalRequest): ParkedApproval {
        val events = java.util.Collections.synchronizedList(mutableListOf<ThreadEvent>())
        val emitter = ThreadEmitter("thr-1") { events.add(it) }
        val token = CancellationToken()
        val result = AtomicReference<ApprovalDecision?>(null)
        val done = CountDownLatch(1)
        val worker = thread(start = false, isDaemon = true) {
            try {
                result.set(state.requestApproval(emitter, token, request))
            } finally {
                done.countDown()
            }
        }
        worker.start()
        return ParkedApproval(events, token, result, done)
    }

    given("a worker parked on an approval request") {
        val state = approvalState()
        val p = park(state, ApprovalRequest.RunCommand("ls", null, 30))

        `when`("the human answers once the card raised its buttons") {
            awaitUntil("an ApprovalRequested is published against the open card") {
                snap(p.events).any { it is ApprovalRequested }
            }
            val itemId = (snap(p.events).first { it is ApprovalRequested } as ApprovalRequested).itemId
            state.resolveApproval(itemId, ApprovalDecision.Accept)
            p.done.await(3, TimeUnit.SECONDS) shouldBe true

            then("the worker wakes with that decision") {
                p.result.get() shouldBe ApprovalDecision.Accept
            }
            then("the request closes with a resolved event carrying the same decision") {
                val resolved = snap(p.events).filterIsInstance<ApprovalResolved>().single()
                resolved.itemId shouldBe itemId
                resolved.decision shouldBe ApprovalDecision.Accept
            }
        }
    }

    given("a worker parked on an approval request the reader decides to abandon") {
        val state = approvalState()
        val p = park(state, ApprovalRequest.RunCommand("ls", null, 30))

        `when`("a Stop arrives while the wait is open") {
            awaitUntil("the request reached the screen before stopping") {
                snap(p.events).any { it is ApprovalRequested }
            }
            p.token.cancel()
            p.done.await(3, TimeUnit.SECONDS) shouldBe true

            then("the wait is released as a cancel rather than left parked on a turn that is gone") {
                p.result.get() shouldBe ApprovalDecision.Cancel
            }
            then("the card is closed with the cancel, not silently left with live buttons") {
                snap(p.events).filterIsInstance<ApprovalResolved>().single().decision shouldBe ApprovalDecision.Cancel
            }
        }
    }

    given("a Stop that races a late click on the same request") {
        val state = approvalState()
        val p = park(state, ApprovalRequest.RunCommand("ls", null, 30))

        `when`("a Stop lands first and the click lands second") {
            awaitUntil("the request reached the screen") { snap(p.events).any { it is ApprovalRequested } }
            val itemId = (snap(p.events).first { it is ApprovalRequested } as ApprovalRequested).itemId
            p.token.cancel()
            p.done.await(3, TimeUnit.SECONDS) shouldBe true
            // The turn is already gone; the late click must not resurrect it.
            state.resolveApproval(itemId, ApprovalDecision.Accept)

            then("the abandoned turn keeps the cancel, not the late accept") {
                p.result.get() shouldBe ApprovalDecision.Cancel
            }
        }
    }

    given("an answer for a request that is no longer pending") {
        val state = approvalState()

        `when`("a stale card clicks after its turn cleared") {
            then("answering an unknown id is a no-op that cannot block or throw") {
                runCatching { state.resolveApproval("never-pending", ApprovalDecision.Accept) }.isFailure shouldBe false
            }
        }
    }

    given("an Ask-mode conversation whose model asks to edit a file") {
        `when`("the human applies the patch from the card") {
            val f = eventFixture(ToolMode.Ask) { gw ->
                gw.actions.add { _, _ ->
                    Turn(
                        "```baudet-tool\n{\"tool\":\"edit_file\",\"arguments\":{\"file\":\"a.txt\",\"edits\":[" +
                            "{\"old_text\":\"one\",\"new_text\":\"two\"}]}}\n```",
                        "resp-1", null, "response.completed"
                    )
                }
                gw.actions.add { _, _ -> Turn("done", "resp-2", null, "response.completed") }
            }
            val events = java.util.Collections.synchronizedList(mutableListOf<ThreadEvent>())
            val worker = thread(start = false, isDaemon = true) {
                runCatching { f.state.send("change it", onDelta = {}, onEvent = { events.add(it) }) }
            }
            worker.start()
            awaitUntil("an ApplyPatch approval is raised for the write") {
                snap(events).any { it is ApprovalRequested && it.request is ApprovalRequest.ApplyPatch }
            }
            val itemId = (snap(events).first { it is ApprovalRequested } as ApprovalRequested).itemId
            f.state.resolveApproval(itemId, ApprovalDecision.Accept)
            worker.join(3_000)

            then("the patch the card previewed is really written") {
                Files.readString(f.folder.resolve("a.txt")) shouldContain "two"
            }
            then("the write card completes and the resolved card records the apply") {
                val change = snap(events).filterIsInstance<ItemCompleted>().single { it.item is FileChangeItem }
                (change.item as FileChangeItem).path shouldBe "a.txt"
                snap(events).filterIsInstance<ApprovalResolved>().single().decision shouldBe ApprovalDecision.Accept
            }
        }
        `when`("the human declines the patch from the card") {
            val f = eventFixture(ToolMode.Ask) { gw ->
                gw.actions.add { _, _ ->
                    Turn(
                        "```baudet-tool\n{\"tool\":\"edit_file\",\"arguments\":{\"file\":\"a.txt\",\"edits\":[" +
                            "{\"old_text\":\"one\",\"new_text\":\"two\"}]}}\n```",
                        "resp-1", null, "response.completed"
                    )
                }
                gw.actions.add { _, _ -> Turn("understood", "resp-2", null, "response.completed") }
            }
            val events = java.util.Collections.synchronizedList(mutableListOf<ThreadEvent>())
            val worker = thread(start = false, isDaemon = true) {
                runCatching { f.state.send("change it", onDelta = {}, onEvent = { events.add(it) }) }
            }
            worker.start()
            awaitUntil("an ApplyPatch approval is raised for the write") {
                snap(events).any { it is ApprovalRequested && it.request is ApprovalRequest.ApplyPatch }
            }
            val itemId = (snap(events).first { it is ApprovalRequested } as ApprovalRequested).itemId
            f.state.resolveApproval(itemId, ApprovalDecision.Decline)
            worker.join(3_000)

            then("the file is left exactly as it was, because a declined patch never touches disk") {
                Files.readString(f.folder.resolve("a.txt")) shouldContain "one"
            }
            then("the decision the card recorded is the decline") {
                snap(events).filterIsInstance<ApprovalResolved>().single().decision shouldBe ApprovalDecision.Decline
            }
        }
    }
})

/** Read a thread-safe copy of an event list a worker may still be appending to. */
private fun snap(events: List<ThreadEvent>): List<ThreadEvent> = synchronized(events) { events.toList() }

private class ParkedApproval(

    val events: MutableList<ThreadEvent>,
    val token: CancellationToken,
    val result: AtomicReference<ApprovalDecision?>,
    val done: CountDownLatch
)

/** A store-backed [AppState] for the handshake tests; [requestApproval] touches none of the session. */
private fun approvalState(): AppState = AppState(H2Store(Files.createTempDirectory("baudet-appr").resolve("db")))

private class ApprovalFixture(val state: AppState, val gateway: ApprovalGateway, val folder: java.nio.file.Path)

/**
 * An [approvalState] wired to a project folder the model may write in, so [AppState] really enters
 * the tool loop. The tool mode is chosen by the test -- Ask is the path that raises a card.
 */
private fun eventFixture(mode: ToolMode, script: (ApprovalGateway) -> Unit): ApprovalFixture {
    val gateway = ApprovalGateway()
    script(gateway)
    val state = AppState(H2Store(Files.createTempDirectory("baudet-apprflow").resolve("db"))).apply {
        config = config.copy(model = "flow-model", toolMode = mode)
        gatewayFactory = { gateway }
    }
    val project = state.addProject("P")
    val folder = Files.createTempDirectory("baudet-apprfolder")
    Files.writeString(folder.resolve("a.txt"), "one\n")
    state.linkFolder(
        project.id,
        folder.toString(),
        Permissions.encode(setOf(FolderPermission.Read, FolderPermission.Write))
    )
    state.newSession(project.id)
    return ApprovalFixture(state, gateway, folder)
}

/** A scripted gateway: one queued action per model turn, so a test drives exactly what was said. */
private class ApprovalGateway : ModelGateway {
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
