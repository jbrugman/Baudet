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
import dev.baudet.thread.ThreadEvent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The git write switch, end to end through the inline approval loop. A git write is a real change
 * to the repository, so it must hold on every promise at once: offered only when the user switched
 * git writes on, gated again on the folder's Write right, blocked on a person's answer on the tool's
 * own card, and only then run. These drive a throwaway git repository through that path -- accept,
 * decline, and the switch off -- because a guard that only looks right in isolation is not a safety
 * boundary for a model that can ask to commit.
 */
class AppStateGitWriteSpec : BehaviorSpec({

    beforeEach { Texts.language = Language.EN }
    afterEach { Texts.language = Language.EN }

    fun awaitUntil(label: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        withClue(label) { cond() shouldBe true }
    }

    fun snap(events: List<ThreadEvent>): List<ThreadEvent> = synchronized(events) { events.toList() }

    fun staged(dir: Path): String =
        ProcessBuilder("git", "-C", dir.toString(), "diff", "--cached", "--name-only")
            .redirectErrorStream(true).start().inputStream.readBytes().decodeToString().trim()

    given("a git repo the user switched git writes on, with Ask mode and the Write right") {
        `when`("the model asks to stage a file and the human accepts on its card") {
            val f = fixture(gitWrites = true)
            f.gateway.actions.add { _, _ ->
                Turn(
                    "```baudet-tool\n{\"tool\":\"git\",\"arguments\":{\"command\":\"add\",\"args\":[\"a.txt\"]}}\n```",
                    "resp-1", null, "response.completed"
                )
            }
            f.gateway.actions.add { _, _ -> Turn("staged", "resp-2", null, "response.completed") }
            val events = java.util.Collections.synchronizedList(mutableListOf<ThreadEvent>())
            val worker = thread(start = false, isDaemon = true) {
                runCatching { f.state.send("stage it", onDelta = {}, onEvent = { events.add(it) }) }
            }
            worker.start()
            awaitUntil("a RunCommand approval is raised for the git write") {
                snap(events).any { it is ApprovalRequested && it.request is ApprovalRequest.RunCommand }
            }
            val itemId = (snap(events).first { it is ApprovalRequested } as ApprovalRequested).itemId
            f.state.resolveApproval(itemId, ApprovalDecision.Accept)
            worker.join(3_000)

            then("the file really lands in the index, because the approved write ran") {
                staged(f.folder) shouldBe "a.txt"
            }
        }

        `when`("the model asks to stage a file and the human declines") {
            val f = fixture(gitWrites = true)
            f.gateway.actions.add { _, _ ->
                Turn(
                    "```baudet-tool\n{\"tool\":\"git\",\"arguments\":{\"command\":\"add\",\"args\":[\"a.txt\"]}}\n```",
                    "resp-1", null, "response.completed"
                )
            }
            f.gateway.actions.add { _, _ -> Turn("understood", "resp-2", null, "response.completed") }
            val events = java.util.Collections.synchronizedList(mutableListOf<ThreadEvent>())
            val worker = thread(start = false, isDaemon = true) {
                runCatching { f.state.send("stage it", onDelta = {}, onEvent = { events.add(it) }) }
            }
            worker.start()
            awaitUntil("a RunCommand approval is raised for the git write") {
                snap(events).any { it is ApprovalRequested && it.request is ApprovalRequest.RunCommand }
            }
            val itemId = (snap(events).first { it is ApprovalRequested } as ApprovalRequested).itemId
            f.state.resolveApproval(itemId, ApprovalDecision.Decline)
            worker.join(3_000)

            then("nothing is staged, because a declined write never runs") {
                staged(f.folder) shouldBe ""
            }
        }
    }

    given("the same repo with git writes switched off") {
        `when`("the model still asks to stage a file") {
            val f = fixture(gitWrites = false)
            f.gateway.actions.add { _, _ ->
                Turn(
                    "```baudet-tool\n{\"tool\":\"git\",\"arguments\":{\"command\":\"add\",\"args\":[\"a.txt\"]}}\n```",
                    "resp-1", null, "response.completed"
                )
            }
            f.gateway.actions.add { _, _ -> Turn("understood", "resp-2", null, "response.completed") }
            val events = java.util.Collections.synchronizedList(mutableListOf<ThreadEvent>())
            val worker = thread(start = false, isDaemon = true) {
                runCatching { f.state.send("stage it", onDelta = {}, onEvent = { events.add(it) }) }
            }
            worker.start()
            worker.join(3_000)

            then("no approval is raised, because a disabled write is refused before it can ask") {
                snap(events).none { it is ApprovalRequested }
            }
            then("nothing is staged") {
                staged(f.folder) shouldBe ""
            }
        }
    }
})

private class GitWriteFixture(val state: AppState, val gateway: ScriptedGateway, val folder: Path)

/**
 * An [AppState] wired to a throwaway git repository the model may read and write, so the tool loop
 * really reaches the git tool. [gitWrites] mirrors the Settings switch that turns the write verbs on.
 */
private fun fixture(gitWrites: Boolean): GitWriteFixture {
    val gateway = ScriptedGateway()
    val state = AppState(H2Store(Files.createTempDirectory("baudet-gitwrite").resolve("db"))).apply {
        config = config.copy(model = "flow-model", toolMode = ToolMode.Ask, gitAllowWrites = gitWrites)
        gatewayFactory = { gateway }
    }
    val project = state.addProject("P")
    val folder = Files.createTempDirectory("baudet-gitwrite-repo")
    Files.writeString(folder.resolve("a.txt"), "one\n")
    ProcessBuilder("git", "-C", folder.toString(), "init", "-q").redirectErrorStream(true)
        .start().apply { inputStream.readBytes() }.waitFor(15, TimeUnit.SECONDS)
    state.linkFolder(
        project.id,
        folder.toString(),
        Permissions.encode(setOf(FolderPermission.Read, FolderPermission.Write))
    )
    state.newSession(project.id)
    return GitWriteFixture(state, gateway, Paths.get(folder.toString()))
}

/** A scripted gateway: one queued action per model turn, so a test drives exactly what was said. */
private class ScriptedGateway : ModelGateway {
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
