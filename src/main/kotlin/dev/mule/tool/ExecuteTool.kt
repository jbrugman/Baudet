package dev.mule.tool

import dev.mule.api.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit

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
 * script. Output is capped and drained concurrently so a chatty command cannot hang the
 * loop or flood the context. A non-zero exit is model-facing feedback, never an exception.
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
        if (!ctx.approveCommand(command, cwd.toString(), timeout))
            return ToolResult(ok = false, output = "run_command: the user declined to run this command")

        val script = runCatching { Files.createTempFile(temp, "mule-run-", ".sh") }
            .getOrElse { return fail("the run script could not be staged in the temp folder") }
        if (!ExecuteWorkspace.withinAllowed(script, listOf(temp, cwd))) {
            runCatching { Files.deleteIfExists(script) }
            return fail("the run script did not stage inside the allowed folders")
        }

        try {
            Files.writeString(script, "#!/bin/bash\n$command\n")
            val process = ProcessBuilder("/bin/bash", script.toAbsolutePath().toString())
                .directory(cwd.toFile())
                .redirectErrorStream(false)
                .apply { environment()["TMPDIR"] = temp.toString() }
                .start()

            var out = Captured("", false, 0)
            var err = Captured("", false, 0)
            val outThread = Thread { out = readCapped(process.inputStream) }
            val errThread = Thread { err = readCapped(process.errorStream) }
            outThread.start()
            errThread.start()

            val finished = process.waitFor(timeout.toLong(), TimeUnit.SECONDS)
            val timedOut = !finished
            if (timedOut) process.destroyForcibly()
            outThread.join(JOIN_MILLIS)
            errThread.join(JOIN_MILLIS)
            val exit = if (timedOut) null else process.exitValue()

            val head = if (timedOut) "run_command timed out after ${timeout}s (no exit code)"
            else "exit code: $exit"
            val body = buildString {
                append(head)
                append(section("stdout", out))
                append(section("stderr", err))
                if (out.length == 0 && err.length == 0) append("\n(no output)")
            }
            return ToolResult(ok = !timedOut && exit == 0, output = body)
        } catch (_: Exception) {
            return fail("the command could not be started or completed")
        } finally {
            runCatching { Files.deleteIfExists(script) }
        }
    }

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun section(label: String, cap: Captured): String {
        if (cap.length == 0) return ""
        val marker = if (cap.truncated) " (truncated, ${cap.length} bytes read)" else ""
        return "\n--- $label$marker ---\n" + cap.text
    }

    /** Drains a stream fully (so the process never blocks on a full pipe) while keeping only a cap. */
    private fun readCapped(input: InputStream): Captured {
        val buffer = ByteArray(8192)
        val kept = ByteArrayOutputStream()
        var total = 0
        var truncated = false
        while (true) {
            val n = runCatching { input.read(buffer) }.getOrDefault(-1)
            if (n < 0) break
            total += n
            if (kept.size() < OUTPUT_CAP_BYTES) {
                val room = OUTPUT_CAP_BYTES - kept.size()
                kept.write(buffer, 0, minOf(n, room))
                if (total > OUTPUT_CAP_BYTES) truncated = true
            } else {
                truncated = true
            }
        }
        return Captured(kept.toString(Charsets.UTF_8), truncated, total)
    }

    private fun fail(reason: String): ToolResult = ToolResult(ok = false, output = "run_command: $reason")

    private class Captured(val text: String, val truncated: Boolean, val length: Int)

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 120
        private const val MAX_TIMEOUT_SECONDS = 600
        private const val JOIN_MILLIS = 1_000L
        private const val OUTPUT_CAP_BYTES = 64 * 1024
    }
}
