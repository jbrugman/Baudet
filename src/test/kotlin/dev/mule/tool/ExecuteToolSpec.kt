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
        // A pinned signature and the opt-in safe gate must skip the prompt only for what they cover;
        // everything else still asks, so neither can quietly widen to an unsafe command.
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
        `when`("a command whose signature is pinned runs with the approver set to deny") {
            val ctx = ToolContext(
                root, writeGranted = false, approve = { _, _ -> false }, execGranted = true,
                approveCommand = { _, _, _ -> false }, autoAllow = { setOf("true") }
            )
            then("it runs, because its signature is pinned to the list") {
                val result = tool.execute(commandArgs("true"), ctx)
                result.ok shouldBe true
                result.output shouldContain "exit code: 0"
            }
        }
        `when`("a command is pinned after this context was already built") {
            // The shape of a real turn: one context, many rounds, and "Always allow" clicked
            // halfway through. A list copied when the context was built asks the user about the
            // very command they just pinned, which is what this case exists to keep out.
            val pinned = mutableListOf("true")
            val ctx = ToolContext(
                root, writeGranted = false, approve = { _, _ -> false }, execGranted = true,
                approveCommand = { _, _, _ -> false }, autoAllow = { pinned.toSet() }
            )
            then("the next call honours it without a new context") {
                pinned += "echo"
                val result = tool.execute(commandArgs("echo pinned mid-turn"), ctx)
                result.ok shouldBe true
                result.output shouldContain "pinned mid-turn"
            }
        }
        `when`("a quoted read runs with its signature pinned and the approver set to deny") {
            // The original nag: a read such as sed with a quoted range re-prompted on every
            // variation. Its signature now covers any such read, so one Always ends the repetition.
            Files.writeString(root.resolve("notes.txt"), "line one\nline two\n")
            val ctx = ToolContext(
                root, writeGranted = false, approve = { _, _ -> false }, execGranted = true,
                approveCommand = { _, _, _ -> false }, autoAllow = { setOf("sed -n") }
            )
            then("it runs, because a quoted argument does not change the signature") {
                val result = tool.execute(commandArgs("sed -n '1,2p' notes.txt"), ctx)
                result.ok shouldBe true
                result.output shouldContain "line one"
            }
        }
        `when`("the write flag of that program runs with only the read signature pinned") {
            // Pinning sed -n must not quietly trust sed -i, which rewrites a file in place.
            Files.writeString(root.resolve("notes.txt"), "keep me\n")
            val ctx = ToolContext(
                root, writeGranted = false, approve = { _, _ -> false }, execGranted = true,
                approveCommand = { _, _, _ -> false }, autoAllow = { setOf("sed -n") }
            )
            then("it is declined, because that flag signs to a different signature") {
                val result = tool.execute(commandArgs("sed -i 's/keep/write/' notes.txt"), ctx)
                result.ok shouldBe false
                result.output shouldContain "declined"
            }
        }
        `when`("a chained command is pinned by its whole text and re-run") {
            // The exact nag from the field: clicking Always on a chained command must end the
            // repetition for that very chain, so a compound command is trusted verbatim rather
            // than by a signature that a different chain could reuse.
            val chain = "pwd && ls -la"
            val ctx = ToolContext(
                root, writeGranted = false, approve = { _, _ -> false }, execGranted = true,
                approveCommand = { _, _, _ -> false }, autoAllow = { setOf(chain) }
            )
            then("it runs, because the whole chain was the thing pinned") {
                val result = tool.execute(commandArgs(chain), ctx)
                result.ok shouldBe true
                result.output shouldContain "exit code: 0"
            }
        }
        `when`("a chained command runs with only its first program pinned") {
            // A short signature pin must never auto-run a chain that hides behind it.
            val ctx = ToolContext(
                root, writeGranted = false, approve = { _, _ -> false }, execGranted = true,
                approveCommand = { _, _, _ -> false }, autoAllow = { setOf("pwd") }
            )
            then("it is declined, because a chain is trusted only as its whole text") {
                val result = tool.execute(commandArgs("pwd && ls -la"), ctx)
                result.ok shouldBe false
                result.output shouldContain "declined"
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
