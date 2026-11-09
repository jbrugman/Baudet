package dev.mule.tool

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * The run tool is fenced by permission and by a human, not by the folder boundary, so
 * those are the guarantees proven here: it refuses without the Execute right, it refuses
 * when the operator declines, and it refuses an empty command before any dialog. The
 * success paths run the real system shell against a temp folder, which exercises the
 * stage-and-run script, the working directory, the exit-code reporting and the timeout
 * kill. A non-zero exit is asserted to be ordinary feedback rather than a crash.
 */
class ExecuteToolSpec : BehaviorSpec({
    val roots = mutableListOf<Path>()

    afterProject { roots.forEach { runCatching { it.toFile().deleteRecursively() } } }

    fun newRoot(name: String): Path {
        val dir = Files.createTempDirectory(name)
        roots.add(dir)
        return dir
    }

    fun commandArgs(command: String, timeoutSeconds: Int? = null): JsonObject = buildJsonObject {
        put("command", command)
        if (timeoutSeconds != null) put("timeout_seconds", timeoutSeconds)
    }

    val tool = ExecuteTool()

    given("a context without the Execute right") {
        val root = newRoot("mule-exec-noperm")
        val ctx = ToolContext(
            root,
            writeGranted = false,
            approve = { _, _ -> false },
            execGranted = false,
            approveCommand = { _, _, _ -> true }
        )
        `when`("a command is requested") {
            then("it refuses before asking or running") {
                val result = tool.execute(commandArgs("echo hi"), ctx)
                result.ok shouldBe false
                result.output shouldContain "Execute"
            }
        }
    }

    given("a granted context whose operator declines") {
        val root = newRoot("mule-exec-deny")
        val ctx = ToolContext(
            root,
            writeGranted = false,
            approve = { _, _ -> false },
            execGranted = true,
            approveCommand = { _, _, _ -> false }
        )
        `when`("a command is requested") {
            then("it declines without running anything") {
                val result = tool.execute(commandArgs("echo hi"), ctx)
                result.ok shouldBe false
                result.output shouldContain "declined"
            }
        }
    }

    given("a granted context whose operator approves") {
        val root = newRoot("mule-exec-run")
        val ctx = ToolContext(
            root,
            writeGranted = false,
            approve = { _, _ -> false },
            execGranted = true,
            approveCommand = { _, _, _ -> true }
        )
        `when`("the command is blank") {
            then("it refuses before asking") {
                val result = tool.execute(commandArgs("   "), ctx)
                result.ok shouldBe false
                result.output shouldContain "command"
            }
        }
        `when`("a benign command runs") {
            then("it reports the output and a zero exit code") {
                val result = tool.execute(commandArgs("echo hello world"), ctx)
                result.ok shouldBe true
                result.output shouldContain "hello world"
                result.output shouldContain "exit code: 0"
            }
        }
        `when`("a command exits non-zero") {
            then("it reports the exit code and is not ok") {
                val result = tool.execute(commandArgs("exit 3"), ctx)
                result.ok shouldBe false
                result.output shouldContain "exit code: 3"
            }
        }
        `when`("a command runs past its timeout") {
            then("it is killed and reported as timed out") {
                val result = tool.execute(commandArgs("sleep 5", 1), ctx)
                result.ok shouldBe false
                result.output shouldContain "timed out"
            }
        }
    }

    given("a granted context that pre-approves without a human") {
        // autoApproveSafe and the always-allow list must skip the prompt only for what they name;
        // everything else still asks, so opting in cannot quietly widen to an unsafe command.
        val root = newRoot("mule-exec-auto")

        `when`("a safe command runs with the safe gate on and the approver set to deny") {
            val ctx = ToolContext(
                root, writeGranted = false, approve = { _, _ -> false }, execGranted = true,
                approveCommand = { _, _, _ -> false }, autoApproveSafe = true
            )
            then("it runs anyway, because the gate recognised it as read-only") {
                val result = tool.execute(commandArgs("echo auto ok"), ctx)
                result.ok shouldBe true
                result.output shouldContain "auto ok"
                result.output shouldContain "exit code: 0"
            }
        }
        `when`("an exact allow-listed command runs with the approver set to deny") {
            val ctx = ToolContext(
                root, writeGranted = false, approve = { _, _ -> false }, execGranted = true,
                approveCommand = { _, _, _ -> false }, autoAllow = setOf("true")
            )
            then("it runs, because the command is pinned to the list") {
                val result = tool.execute(commandArgs("true"), ctx)
                result.ok shouldBe true
                result.output shouldContain "exit code: 0"
            }
        }
        `when`("an unsafe command runs with the safe gate on and the approver set to deny") {
            val ctx = ToolContext(
                root, writeGranted = false, approve = { _, _ -> false }, execGranted = true,
                approveCommand = { _, _, _ -> false }, autoApproveSafe = true
            )
            then("it is declined, because opting into safe commands does not approve anything else") {
                val result = tool.execute(commandArgs("exit 3"), ctx)
                result.ok shouldBe false
                result.output shouldContain "declined"
            }
        }
    }
})
