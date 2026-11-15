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
 * Lets the model work with the project git repository: a fixed set of read-only commands --
 * status, diff, log, blame and a few more -- and, when [writesAllowed] is on, a small set of
 * verbs that change it (add, commit, checkout, stash, push). A coding assistant that can edit
 * files but cannot see or record what git thinks of those edits is half a tool, so this closes
 * the loop the read and edit tools leave open. It is fenced three ways. First, it is offered
 * only when the project grants the Read right, and the write verbs are not even advertised unless
 * the user turned git writes on in Settings. Second, every call goes through [GitCommand], which
 * refuses unknown verbs, global flags and any write shape it cannot bound, and runs a bounded
 * argv, so the model cannot rewrite history or point git at another repository. Third, a write
 * verb runs only after the human approves it on its card -- the same blocking path a shell command
 * uses -- and only when the project also grants the Write right, so turning git writes on in
 * Settings still never writes somewhere the folder was not allowed to. The run itself, with its
 * concurrent capped drain, is delegated to [ProcessRunner], as in [ExecuteTool], so a large diff
 * cannot flood the context. A non-zero exit is model-facing feedback, not an exception. The text
 * it returns stays English because the model reads it.
 */
class GitTool(private val writesAllowed: Boolean = false) : Tool {

    override val name: String = "git"

    override val description: String = if (writesAllowed)
        "Run a git command in the project repository. Read-only commands: status, diff, " +
            "diff_staged, log, show, branch, ls_files, rev_parse, blame. Write commands: add, " +
            "commit, checkout, stash, push -- each runs only after the user approves it. For " +
            "add, pass the paths in args; for commit, pass the message in the message field; for " +
            "checkout, one branch (optionally with -b to create it); for stash, one of push, pop, " +
            "apply, save or list; for push, an optional remote and branch. Global flags and " +
            "anything these commands cannot do stay a job for run_command. Returns stdout, " +
            "stderr and the exit code."
    else
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
                put("enum", buildJsonArray { GitCommand.advertised(writesAllowed).forEach { add(it) } })
                put("description", "the git command to run")
            })
            put("args", buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
                put("description", "optional extra arguments or paths for the command")
            })
            if (writesAllowed) put("message", buildJsonObject {
                put("type", "string")
                put("description", "the commit message, for the commit command only")
            })
        })
        put("required", buildJsonArray { add("command") })
    }

    override fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!ctx.readGranted) return fail("the project does not grant the Read right")

        val command = args.str("command")?.takeIf { it.isNotBlank() }
            ?: return fail("needs a command argument")
        val extra = stringList(args["args"])
        val message = args.str("message")

        val refusal = GitCommand.checkAllowed(command, extra, writesAllowed, message)
        if (refusal != null) return ToolResult(ok = false, output = refusal)

        val rootPath = ctx.root ?: return fail("no project folder is linked to inspect")

        // A write verb is a real change to the repository, so it is gated twice: the folder must
        // grant Write (a git write is a write to the project), and a person must approve this exact
        // command on its card, exactly as a shell command is. A command the user already pinned with
        // Always -- and whose stored signature matches this one -- runs without asking again.
        if (GitCommand.isWrite(command)) {
            if (!ctx.writeGranted) return fail("the project does not grant the Write right")
            val summary = GitCommand.summary(command, extra, message)
            val pinned = CommandSignature.pinned(summary) in ctx.autoAllow()
            if (!pinned && !ctx.approveCommand(summary, rootPath.toString(), TIMEOUT_SECONDS))
                return fail("the user declined this git write")
        }

        return try {
            val result = ProcessRunner.run(
                argv = GitCommand.argv(
                    rootPath.toAbsolutePath().toString(), command, extra, message
                ),
                dir = rootPath.toFile(),
                timeoutSeconds = TIMEOUT_SECONDS.toLong()
            )
            val head = if (result.timedOut) "git timed out after ${TIMEOUT_SECONDS}s (no exit code)"
            else "exit code: ${result.exitCode}"
            ToolResult(
                ok = !result.timedOut && result.exitCode == 0,
                output = head + result.body(),
                exitCode = result.exitCode,
                timedOut = result.timedOut,
                durationMs = result.durationMs,
                cwd = rootPath.toString()
            )
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
        private const val TIMEOUT_SECONDS = 15
    }
}
