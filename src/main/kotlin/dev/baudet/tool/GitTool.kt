package dev.baudet.tool

import dev.baudet.api.str
import dev.baudet.git.GitCommand
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Lets the model inspect the project git repository through a fixed set of read-only
 * commands: status, diff, log, blame and a few more. A coding assistant that can edit files
 * but cannot see what git thinks of those edits is half a tool, so this closes the loop the
 * read and edit tools leave open. It is fenced in two ways. First, it is offered only when
 * the project grants the Read right, and it never writes. Second, every call goes through
 * [GitCommand], which refuses mutating verbs and global flags and runs a bounded argv, so
 * the model cannot rewrite history or point git at another repository. The run itself, with
 * its concurrent capped drain, is delegated to [ProcessRunner], exactly as in [ExecuteTool],
 * so a large diff cannot flood the context or hang the loop on a full pipe. A non-zero exit
 * is model-facing feedback, not an exception. The text it returns stays English because the
 * model reads it.
 */
class GitTool : Tool {

    override val name: String = "git"

    override val description: String =
        "Run a read-only git command in the project repository. Available commands: " +
            "status, diff, diff_staged, log, show, branch, ls_files, rev_parse, blame. " +
            "Optional args are extra read-only arguments or paths (for example a file path " +
            "for diff, or a commit for show). Anything that changes the repository must go " +
            "through run_command instead. Returns stdout, stderr and the exit code."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("command", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { GitCommand.advertised.forEach { add(it) } })
                put("description", "the read-only git command to run")
            })
            put("args", buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
                put("description", "optional extra read-only arguments or paths")
            })
        })
        put("required", buildJsonArray { add("command") })
    }

    override fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!ctx.readGranted) return fail("the project does not grant the Read right")

        val command = args.str("command")?.takeIf { it.isNotBlank() }
            ?: return fail("needs a command argument")
        val extra = stringList(args["args"])

        val refusal = GitCommand.checkAllowed(command, extra)
        if (refusal != null) return ToolResult(ok = false, output = refusal)

        val rootPath = ctx.root ?: return fail("no project folder is linked to inspect")

        return try {
            val result = ProcessRunner.run(
                argv = GitCommand.argv(rootPath.toAbsolutePath().toString(), command, extra),
                dir = rootPath.toFile(),
                timeoutSeconds = TIMEOUT_SECONDS
            )
            val head = if (result.timedOut) "git timed out after ${TIMEOUT_SECONDS}s (no exit code)"
            else "exit code: ${result.exitCode}"
            ToolResult(ok = !result.timedOut && result.exitCode == 0, output = head + result.body())
        } catch (_: Exception) {
            fail("the git command could not be started or completed")
        }
    }

    /** The optional args array as a plain string list, ignoring non-string entries. */
    private fun stringList(element: kotlinx.serialization.json.JsonElement?): List<String> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }

    private fun fail(reason: String): ToolResult = ToolResult(ok = false, output = "git: $reason")

    companion object {
        private const val TIMEOUT_SECONDS = 15L
    }
}
