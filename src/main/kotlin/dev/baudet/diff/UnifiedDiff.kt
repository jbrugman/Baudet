package dev.baudet.diff

import java.io.StringWriter

/**
 * Renders [LineDiff.Op]s as plain unified-diff text. It is the one place a diff
 * becomes lines a human reads, so the approval card and the later file-tree patch
 * view color the same text instead of each inventing a format. Git's own
 * `git diff` is already in this shape and needs no renderer: it only goes through
 * the same coloring.
 */
object UnifiedDiff {

    /** A minimal unified header plus the body; context lines carry a space, adds a +, removes a -. */
    fun render(oldName: String, newName: String, ops: List<LineDiff.Op>): String {
        val out = StringWriter()
        out.write("--- $oldName\n+++ $newName\n")
        ops.forEach { op ->
            when (op) {
                is LineDiff.Op.Context -> out.write(" " + op.text + "\n")
                is LineDiff.Op.Add -> out.write("+" + op.text + "\n")
                is LineDiff.Op.Remove -> out.write("-" + op.text + "\n")
            }
        }
        return out.toString()
    }
}
