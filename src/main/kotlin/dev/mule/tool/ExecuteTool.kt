package dev.mule.tool

import dev.mule.api.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files

/**
 * Runs a shell command in the project folder so the model can build, test or inspect a
 * real project, not only read and edit its files. This is the most powerful and the
 * least fenced tool: a subprocess cannot be trapped by [FolderBoundary], so two things
 * fence it instead. First, it is offered only when the project grants the Execute right.
 * Second, every command needs the human to approve it, even in Auto mode, because once
 * bytes reach a shell the folder boundary is no longer ours to enforce. To stay useful it
 * does two things around the run: it uses the project folder as the working directory and
 * the system temp folder as TMPDIR, so scripts a command writes land where we expect, and
 * it checks both folders through [ExecuteWorkspace] before it stages the command as a temp
 * script. The run itself, with its concurrent capped drain, is delegated to
 * [ProcessRunner]. A non-zero exit is model-facing feedback, never an exception.
 */
class ExecuteTool : Tool {

    override val name: String = "run_command"

    override val description: String =
        "Run a shell command (bash; python or other interpreters may be invoked from it) " +
            "with the project folder as its working directory. The command runs only " +
            "after the user approves it. Scripts written to the system temp folder are " +
            "allowed. Returns stdout, stderr and the exit code."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("command", buildJsonObject {
                put("type", "string")
                put("description", "the shell command to run in the project folder")
            })
            put("timeout_seconds", buildJsonObject {
                put("type", "integer")
                put("description", "optional run timeout in seconds (default 120, max 600)")
            })
        })
        put("required", buildJsonArray { add("command") })
    }

    override fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!ctx.execGranted) return fail("the project does not grant the Execute right")

        val command = args.str("command")?.takeIf { it.isNotBlank() }
            ?: return fail("needs a command argument")

        val root = ctx.root ?: return fail("no project folder is linked to run in")
        val cwd = ExecuteWorkspace.projectDir(root)
            ?: return fail("the project folder is not an existing directory")
        val temp = ExecuteWorkspace.tempDir()
            ?: return fail("no writable temp folder is available to stage the run")

        val timeout = args.intOrNull("timeout_seconds")?.coerceIn(1, MAX_TIMEOUT_SECONDS)
            ?: DEFAULT_TIMEOUT_SECONDS
        // A command the user pinned to the always-allow list, or one the opt-in safe-command gate
        // recognises as read-only, skips the prompt. Everything else is still confirmed by a human,
        // because a subprocess cannot be fenced by the folder boundary the way a file write can.
        val preApproved = command in ctx.autoAllow ||
            (ctx.autoApproveSafe && SafeCommands.isSafe(command))
        if (!preApproved && !ctx.approveCommand(command, cwd.toString(), timeout))
            return ToolResult(ok = false, output = "run_command: the user declined to run this command")

        val script = runCatching { Files.createTempFile(temp, "mule-run-", ".sh") }
            .getOrElse { return fail("the run script could not be staged in the temp folder") }
        if (!ExecuteWorkspace.withinAllowed(script, listOf(temp, cwd))) {
            runCatching { Files.deleteIfExists(script) }
            return fail("the run script did not stage inside the allowed folders")
        }

        return try {
            Files.writeString(script, "#!/bin/bash\n$command\n")
            val result = ProcessRunner.run(
                argv = listOf("/bin/bash", script.toAbsolutePath().toString()),
                dir = cwd.toFile(),
                timeoutSeconds = timeout.toLong(),
                configure = { pb -> pb.environment()["TMPDIR"] = temp.toString() }
            )
            val head = if (result.timedOut) "run_command timed out after ${timeout}s (no exit code)"
            else "exit code: ${result.exitCode}"
            ToolResult(ok = !result.timedOut && result.exitCode == 0, output = head + result.body())
        } catch (_: Exception) {
            fail("the command could not be started or completed")
        } finally {
            runCatching { Files.deleteIfExists(script) }
        }
    }

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun fail(reason: String): ToolResult = ToolResult(ok = false, output = "run_command: $reason")

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 120
        private const val MAX_TIMEOUT_SECONDS = 600
    }
}
