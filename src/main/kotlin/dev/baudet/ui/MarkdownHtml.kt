package dev.baudet.ui

/**
 * A tiny markdown-to-HTML converter for curated, human-authored text, used by the About
 * dialog. It is deliberately separate from [Markdown]: that parser streams token-by-token into
 * a styled Swing document for live model output and never makes links clickable, whereas the
 * About body is fixed prose that should render headings, bold, italic, code, bullet lists and
 * working links. Keeping the two apart means this one can be simple and testable without
 * touching the transcript path at all.
 *
 * The supported subset is the whole of what About needs: paragraphs separated by blank lines,
 * bullet lines starting with a hyphen, inline `code`, **bold**, *italic* and [text](url) links.
 * Everything else is treated as plain text, and HTML-significant characters are escaped first,
 * so a stray angle bracket can never inject markup.
 */
object MarkdownHtml {

    /** Convert a markdown document to a complete, self-styled HTML string for a JEditorPane. */
    fun toHtml(markdown: String): String =
        "<html><head><style>" + STYLE + "</style></head><body>" +
            renderBlocks(markdown) +
            "</body></html>"

    /** The visible body only (no html/head/body wrapper), so callers can embed it elsewhere. */
    fun render(markdown: String): String = renderBlocks(markdown)

    private const val STYLE = """
        body { font-family: sans-serif; font-size: 13px; color: #d8d8d8; background: #2b2b2b;
               margin: 8px 10px; line-height: 1.45; }
        p { margin: 0 0 10px 0; }
        ul { margin: 0 0 10px 0; padding-left: 22px; }
        li { margin: 0 0 6px 0; }
        code { font-family: monospace; background: #3c3f41; padding: 1px 4px; }
        a { color: #6cb2ff; }
        strong { color: #f0f0f0; }
    """

    private fun renderBlocks(markdown: String): String {
        val out = StringBuilder()
        var inList = false
        for (raw in markdown.split('\n')) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> if (inList) { out.append("</ul>"); inList = false }
                line.startsWith("- ") -> {
                    if (!inList) { out.append("<ul>"); inList = true }
                    out.append("<li>").append(inline(line.substring(2))).append("</li>")
                }
                else -> {
                    if (inList) { out.append("</ul>"); inList = false }
                    out.append("<p>").append(inline(line)).append("</p>")
                }
            }
        }
        if (inList) out.append("</ul>")
        return out.toString()
    }

    /** Escape first, then apply inline spans in a fixed order: code, link, bold, italic. */
    private fun inline(text: String): String {
        var t = escape(text)
        t = t.replace(Regex("`([^`]+)`"), "<code>$1</code>")
        t = t.replace(Regex("\\[([^\\]]+)]\\(([^)]+)\\)"), "<a href=\"$2\">$1</a>")
        t = t.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<strong>$1</strong>")
        t = t.replace(Regex("\\*([^*]+)\\*"), "<em>$1</em>")
        return t
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
