package dev.baudet.tool

import dev.baudet.git.GitCommand
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

/**
 * Drives the git tool end to end against a real repository, so the guard, the process run and
 * the model-facing output are checked together rather than only the guard in isolation. Each
 * test runs in its own throwaway repo, removed when the test ends so nothing is left in temp.
 */
class GitToolSpec : FunSpec({

    val tool = GitTool()
    val created = mutableListOf<Path>()

    afterTest { created.forEach { runCatching { it.toFile().deleteRecursively() } }; created.clear() }

    fun tempDir(): Path = createTempDirectory("baudet-git-").also { created.add(it) }

    fun ctx(root: Path?, readGranted: Boolean) = ToolContext(
        root = root, writeGranted = false, approve = { _, _ -> false }, readGranted = readGranted
    )

    fun writeCtx(root: Path?, approved: Boolean, autoAllow: Set<String> = emptySet()) = ToolContext(
        root = root, writeGranted = true, approve = { _, _ -> false }, readGranted = true,
        approveCommand = { _, _, _ -> approved }, autoAllow = { autoAllow }
    )

    /** The paths the index currently holds, read straight from the throwaway repo. */
    fun staged(dir: Path): String =
        ProcessBuilder("git", "-C", dir.toString(), "diff", "--cached", "--name-only")
            .redirectErrorStream(true).start().inputStream.readBytes().decodeToString().trim()

    fun args(command: String, vararg extra: String): JsonObject = buildJsonObject {
        put("command", command)
        if (extra.isNotEmpty()) put("args", buildJsonArray { extra.forEach { add(JsonPrimitive(it)) } })
    }

    test("status reports a tracked modification against a real repo") {
        val dir = tempDir()
        runGit(dir, "init", "-q")
        runGit(dir, "config", "user.email", "baudet@example.test")
        runGit(dir, "config", "user.name", "Baudet Test")
        val file = dir.resolve("a.txt")
        Files.writeString(file, "one\n")
        runGit(dir, "add", "a.txt")
        runGit(dir, "commit", "-qm", "seed")
        Files.writeString(file, "two\n")

        val result = tool.execute(args("status"), ctx(dir.toAbsolutePath(), true))
        result.ok shouldBe true
        result.output shouldContain "exit code: 0"
        result.output shouldContain "a.txt"
    }

    test("a read-only inspection of a clean repo still reports success") {
        val dir = tempDir()
        runGit(dir, "init", "-q")
        val result = tool.execute(args("status"), ctx(dir.toAbsolutePath(), true))
        result.ok shouldBe true
        result.output shouldContain "exit code: 0"
    }

    test("the tool refuses without the Read right") {
        val result = tool.execute(args("status"), ctx(tempDir().toAbsolutePath(), false))
        result.ok shouldBe false
        result.output shouldContain "Read"
    }

    test("the tool refuses a mutating verb before running it") {
        val result = tool.execute(args("commit"), ctx(tempDir().toAbsolutePath(), true))
        result.ok shouldBe false
        result.output shouldContain "run_command"
    }

    test("the tool refuses a smuggled global flag before running it") {
        val result = tool.execute(args("diff", "-c", "x=y"), ctx(tempDir().toAbsolutePath(), true))
        result.ok shouldBe false
        result.output shouldContain "not allowed"
    }

    test("a git write stages the file once the user approves it") {
        val writing = GitTool(writesAllowed = true)
        val dir = tempDir()
        runGit(dir, "init", "-q")
        Files.writeString(dir.resolve("a.txt"), "one\n")

        val result = writing.execute(args("add", "a.txt"), writeCtx(dir.toAbsolutePath(), approved = true))
        result.ok shouldBe true
        staged(dir) shouldBe "a.txt"
    }

    test("a declined git write never stages the file") {
        val writing = GitTool(writesAllowed = true)
        val dir = tempDir()
        runGit(dir, "init", "-q")
        Files.writeString(dir.resolve("a.txt"), "one\n")

        val result = writing.execute(args("add", "a.txt"), writeCtx(dir.toAbsolutePath(), approved = false))
        result.ok shouldBe false
        result.output shouldContain "declined"
        staged(dir) shouldBe ""
    }

    test("a git write is refused when the folder grants no Write right, even with approval") {
        val writing = GitTool(writesAllowed = true)
        val noWrite = ToolContext(
            root = tempDir().toAbsolutePath(), writeGranted = false,
            approve = { _, _ -> false }, readGranted = true, approveCommand = { _, _, _ -> true }
        )
        val result = writing.execute(args("add", "a.txt"), noWrite)
        result.ok shouldBe false
        result.output shouldContain "Write"
    }

    test("a pinned git write skips the prompt, exactly as a pinned shell command does") {
        val writing = GitTool(writesAllowed = true)
        val dir = tempDir()
        runGit(dir, "init", "-q")
        Files.writeString(dir.resolve("a.txt"), "one\n")
        val pin = CommandSignature.pinned(GitCommand.summary("add", listOf("a.txt"), null))

        val result = writing.execute(
            args("add", "a.txt"),
            writeCtx(dir.toAbsolutePath(), approved = false, autoAllow = setOf(pin))
        )
        result.ok shouldBe true
        staged(dir) shouldBe "a.txt"
    }

    test("a read-only tool, the default, never offers the write verb even when asked") {
        // GitTool() builds with writesAllowed=false, mirroring gitAllowWrites off in Settings.
        val result = tool.execute(args("add", "a.txt"), writeCtx(tempDir().toAbsolutePath(), approved = true))
        result.ok shouldBe false
        result.output shouldContain "disabled"
    }
})

private fun runGit(dir: Path, vararg args: String) {
    val p = ProcessBuilder(listOf("git", "-C", dir.toString()) + args)
        .redirectErrorStream(true).start()
    p.inputStream.readBytes()
    p.waitFor(15, TimeUnit.SECONDS) shouldBe true
}
