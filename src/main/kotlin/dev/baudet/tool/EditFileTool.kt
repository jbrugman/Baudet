package dev.baudet.tool

import dev.baudet.api.arr
import dev.baudet.api.str
import dev.baudet.diff.LineDiff
import dev.baudet.diff.UnifiedDiff
import dev.baudet.tool.PatchOutcome.Applied
import dev.baudet.tool.PatchOutcome.Rejected
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * The first tool: patch one file inside a project folder by exact text edits. It
 * enforces the rights the permission model already stores -- a folder must be linked and
 * Write granted -- refuses any path that resolves outside that folder, previews a diff
 * and asks before writing, and keeps the original bytes so the write can be undone.
 * Every rejection is a [ToolResult] with a model-facing reason, never an exception, so
 * the model sees the same failure channel whether the cause is a bad path or a stale
 * match. When a path is wrong it returns a bounded, hidden-free listing of the folder so
 * the model can find the real name on its own instead of guessing.
 */
class EditFileTool : Tool {

    override val name: String = "edit_file"

    override val description: String =
        "Change one file in the project folder by replacing exact text. " +
            "Give the exact old_text to find and the new_text to put in its place. " +
            "old_text must appear once, or be unambiguous when replace_all is true."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("file", buildJsonObject {
                put("type", "string")
                put("description", "a file inside the project folder")
            })
            put("edits", buildJsonObject {
                put("type", "array")
                put("description", "the edits to apply, in order")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("old_text", buildJsonObject { put("type", "string") })
                        put("new_text", buildJsonObject { put("type", "string") })
                        put("replace_all", buildJsonObject { put("type", "boolean") })
                    })
                    put("required", buildJsonArray { add("old_text"); add("new_text") })
                })
            })
        })
        put("required", buildJsonArray { add("file"); add("edits") })
    }

    override fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rel = args.str("file") ?: return fail("needs a file argument")
        val root = ctx.root ?: return fail("no project folder is linked to write into")
        if (!ctx.writeGranted) return fail("write access to the project folder has not been granted")

        val edits = parseEdits(args.arr("edits"))
        if (edits.isEmpty()) return fail("needs at least one edit")

        val resolved = when (val r = FolderBoundary.resolveFileWithin(root, rel)) {
            is FolderBoundary.Resolved.Inside -> r.path
            is FolderBoundary.Resolved.Refused -> return fail(r.reason)
        }

        val original = try {
            Files.readString(resolved)
        } catch (_: Exception) {
            return fail("$rel could not be read.${FolderListing.hint(root)}")
        }

        return when (val outcome = PatchApplier.apply(original, edits)) {
            is Rejected -> fail(outcome.reason)
            is Applied -> writeIfApproved(ctx, root, resolved, rel, original, outcome.content)
        }
    }

    private fun writeIfApproved(
        ctx: ToolContext,
        root: Path,
        resolved: Path,
        rel: String,
        original: String,
        updated: String
    ): ToolResult {
        if (original == updated) {
            return ToolResult(ok = true, output = "no change: new_text equals old_text for $rel")
        }
        val diff = UnifiedDiff.render("a/$rel", "b/$rel", LineDiff.diff(original, updated))
        if (!ctx.approve(rel, diff)) {
            return ToolResult(
                ok = false,
                output = "the user declined the change to $rel; do not retry it, tell the user instead",
                diff = diff
            )
        }
        val safe = FolderBoundary.realWithin(root, resolved)
            ?: return fail("$rel resolves outside the folder once links are followed")
        val current = try {
            Files.readString(safe)
        } catch (_: Exception) {
            return fail("$rel could not be re-read before writing")
        }
        if (current != original) {
            return fail("$rel changed since the preview; not overwriting a newer edit")
        }
        val undo: () -> Unit = { runCatching { Files.writeString(safe, original) } }
        return try {
            Files.writeString(safe, updated)
            ToolResult(ok = true, output = "wrote $rel", diff = diff, undo = undo)
        } catch (e: Exception) {
            fail("writing $rel failed: ${e.message}")
        }
    }

    private fun parseEdits(edits: JsonArray?): List<Edit> =
        edits?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val old = obj.str("old_text") ?: return@mapNotNull null
            val new = obj.str("new_text") ?: return@mapNotNull null
            val all = (obj["replace_all"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
            Edit(old, new, all)
        } ?: emptyList()

    private fun fail(reason: String): ToolResult = ToolResult(ok = false, output = "edit_file: $reason")
}
