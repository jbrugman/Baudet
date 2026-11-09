package dev.baudet.tool

/**
 * Folds a list of [Edit]s into file text in order, each applied to the result of
 * the previous. Pure, so a wrong patch is a failing test rather than a bad write:
 * no filesystem, no threading, no model. Every failure names the edit and why, so
 * the model gets an actionable message instead of a silent no-op.
 */
object PatchApplier {

    /** Applies [edits] to [content] in sequence, or rejects on the first edit that cannot land. */
    fun apply(content: String, edits: List<Edit>): PatchOutcome {
        if (edits.isEmpty()) return PatchOutcome.Rejected("no edits were given")
        var text = content
        edits.forEachIndexed { index, edit ->
            val old = edit.oldText
            if (old.isEmpty()) return PatchOutcome.Rejected("edit ${index + 1}: old_text must not be empty")
            val hits = occurrences(text, old)
            if (hits == 0) return PatchOutcome.Rejected("edit ${index + 1}: old_text was not found")
            if (hits > 1 && !edit.replaceAll) {
                return PatchOutcome.Rejected(
                    "edit ${index + 1}: old_text matches $hits times; add surrounding context or set replace_all"
                )
            }
            text = if (edit.replaceAll) text.replace(old, edit.newText)
            else text.replaceFirst(old, edit.newText)
        }
        return PatchOutcome.Applied(text)
    }

    /** How many non-overlapping times [needle] occurs in [haystack]; -1 for an empty needle. */
    private fun occurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return -1
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }
}
