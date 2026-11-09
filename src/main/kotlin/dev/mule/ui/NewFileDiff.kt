package dev.mule.ui

/**
 * Renders a brand-new, untracked file so the diff viewer can show what is inside it even
 * though git has no committed version to compare against. It reproduces the shape of a git
 * new-file patch: a reference to the null device, an added-file header with the relative
 * name, and every content line prefixed with a plus sign. Anything that is not safe to show
 * -- a missing file, one larger than [MAX_BYTES], one whose bytes are not text, or an empty
 * file -- yields just the caller's intro line, which is the same result the viewer showed
 * before. The function is pure, with no Swing and no disk access, so it can be tested alone.
 */
object NewFileDiff {

    /** Hard cap on bytes accepted, so a huge new file is refused before it is rendered. */
    const val MAX_BYTES = 1_000_000

    /** Cap on characters shown, so one new file cannot crowd out the whole viewer. */
    const val MAX_CHARS = 60_000

    /**
     * @param rel the file path relative to the project folder, used only in the header
     * @param intro the localized message to keep on top, also the fallback for unsafe input
     * @param bytes the file bytes, or null when there is nothing to read
     */
    fun body(rel: String, intro: String, bytes: ByteArray?): String {
        if (bytes == null) return intro
        if (bytes.size > MAX_BYTES) return intro
        if (bytes.contains(0.toByte())) return intro
        val text = String(bytes, Charsets.UTF_8)
        if (text.isEmpty()) return intro

        val truncated = text.length > MAX_CHARS
        val shown = if (truncated) text.substring(0, MAX_CHARS) else text
        return buildString {
            append(intro).append("\n\n")
            append("--- /dev/null\n")
            append("+++ b/").append(rel).append('\n')
            shown.lineSequence().forEach { append('+').append(it).append('\n') }
            if (truncated) append("\n(content truncated)\n")
        }.trimEnd()
    }
}
