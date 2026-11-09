package dev.mule.diff

/**
 * Classifies each line of a unified diff so the viewer can color added and removed text.
 * Pure by design: no Swing and no disk access, so the rules are testable on their own and
 * stay identical for git diff output, [UnifiedDiff.render] text and a [dev.mule.ui.NewFileDiff]
 * body. The order of the checks matters. A real header line carries a space after its triple
 * sign, so a removed line whose own content begins with those signs is not mistaken for one.
 */
object DiffHighlight {

    /** The kind of one raw unified-diff line. */
    fun classify(line: String): DiffLineKind = when {
        line.startsWith("@@") -> DiffLineKind.HunkHeader
        line.startsWith("+++ ") || line.startsWith("--- ") -> DiffLineKind.FileHeader
        line.startsWith("diff --git") || line.startsWith("index ") ||
            line.startsWith("new file mode") || line.startsWith("deleted file mode") ||
            line.startsWith("old mode") || line.startsWith("new mode") ||
            line.startsWith("similarity index") || line.startsWith("rename ") ||
            line.startsWith(BACKSLASH) -> DiffLineKind.Meta
        line.startsWith("+") -> DiffLineKind.Added
        line.startsWith("-") -> DiffLineKind.Removed
        else -> DiffLineKind.Context
    }

    /** The no-newline marker line always starts with a backslash. */
    private const val BACKSLASH = "\\"
}
