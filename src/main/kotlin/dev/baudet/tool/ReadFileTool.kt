package dev.baudet.tool

import dev.baudet.api.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files

/**
 * Reads one text file inside a project folder so the model can see a file before it
 * changes it. It is the read half of the edit loop: without it a model can only guess
 * the current bytes and its patch never matches, which is exactly how a first attempt
 * at a real edit fails. It enforces the same boundary as [EditFileTool] -- a folder must
 * be linked and the path must resolve inside it -- but, being read-only, it needs no
 * Write right, asks nothing, writes nothing and offers no undo. A path that misses
 * returns the bounded folder listing so the model can pick the real name. A file too
 * large to hand back cheaply, or whose bytes are not text, is refused rather than
 * dumped: feeding megabytes or binary noise into the context costs a turn and helps no
 * one. Every refusal is a [ToolResult] with a model-facing reason, never an exception.
 */
class ReadFileTool : Tool {

    override val name: String = "read_file"

    override val description: String =
        "Read a text file inside the project folder and return its current contents. " +
            "Call this before edit_file so your old_text matches the file exactly."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("file", buildJsonObject {
                put("type", "string")
                put("description", "a file inside the project folder")
            })
        })
        put("required", buildJsonArray { add("file") })
    }

    override fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rel = args.str("file") ?: return fail("needs a file argument")
        val root = ctx.root ?: return fail("no project folder is linked to read from")

        val resolved = when (val r = FolderBoundary.resolveFileWithin(root, rel)) {
            is FolderBoundary.Resolved.Inside -> r.path
            is FolderBoundary.Resolved.Refused -> return fail(r.reason)
        }

        val size = runCatching { Files.size(resolved) }.getOrElse { return fail("$rel could not be read") }
        if (size > MAX_FILE_BYTES) return fail("$rel is larger than the ${MAX_FILE_BYTES / 1024} KB read limit")

        val bytes = runCatching { Files.readAllBytes(resolved) }.getOrElse { return fail("$rel could not be read") }
        if (bytes.contains(0.toByte())) return fail("$rel looks like a binary file and is not shown")

        val text = String(bytes, Charsets.UTF_8)
        if (text.isEmpty()) return ToolResult(ok = true, output = "read $rel: the file is empty")

        val truncated = text.length > MAX_CHARS
        val body = if (truncated) text.substring(0, MAX_CHARS) else text
        val header = if (truncated) "read $rel, first $MAX_CHARS of ${text.length} chars:"
        else "read $rel (${text.length} chars):"
        return ToolResult(ok = true, output = header + "\n" + body)
    }

    private fun fail(reason: String): ToolResult = ToolResult(ok = false, output = "read_file: $reason")

    companion object {
        /** Hard cap on the bytes read from disk, so a huge file is refused before it is loaded. */
        private const val MAX_FILE_BYTES = 1_000_000L

        /** Cap on characters handed to the model, so one read cannot crowd out the whole context. */
        private const val MAX_CHARS = 60_000
    }
}
