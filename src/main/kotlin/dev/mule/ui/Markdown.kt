package dev.mule.ui

/**
 * What a run of answer text should look like. The names describe the writer's intent rather
 * than a Swing attribute, so the parser stays free of the toolkit and can be tested alone.
 */
enum class SpanKind { Plain, Bold, Italic, Code, CodeBlock, Heading, Marker }

/** One run of text with the style it asked for. */
data class Span(val text: String, val kind: SpanKind)

/**
 * The subset of markdown that earns its keep in a chat transcript: fenced code, headings,
 * bullets, bold, italic and inline code.
 *
 * Links, tables and nested emphasis are deliberately left literal. Underlining with
 * underscores is left out on purpose as well, because a coding answer is full of identifiers
 * such as max_output_tokens, and turning those into italics would corrupt the one thing the
 * reader needed verbatim. An unmatched marker is never removed either: a model that writes a
 * stray asterisk meant a stray asterisk.
 */
object Markdown {

    /** A fence line: three or more backticks, and nothing else worth keeping. */
    fun isFence(line: String): Boolean = line.trimStart().startsWith("```")

    /** The text after the opening fence, which is the language tag when the model supplied one. */
    fun fenceLanguage(line: String): String? =
        line.trimStart().removePrefix("```").trim().takeIf { it.isNotEmpty() }

    /** The bullet marker of a line, with its trailing space, or null when it is not a bullet. */
    fun bulletMarker(line: String): String? {
        val trimmed = line.trimStart()
        val indent = line.length - trimmed.length
        val bullet = trimmed.take(2)
        if (bullet.length == 2 && bullet[1] == ' ' && bullet[0] in "-*+") return line.take(indent + 2)
        val dot = trimmed.indexOf(". ")
        if (dot > 0 && dot <= 3 && trimmed.take(dot).all { it.isDigit() }) return line.take(indent + dot + 2)
        return null
    }

    /** Heading level 1 to 6, or null when the line is not an atx heading. */
    fun headingLevel(line: String): Int? {
        val trimmed = line.trimStart()
        val level = trimmed.takeWhile { it == '#' }.length
        return if (level in 1..6 && trimmed.length > level && trimmed[level] == ' ') level else null
    }

    /**
     * Split one line into styled runs. Only paired markers become style, and a marker with no
     * partner is emitted as the literal text the reader can still make sense of.
     */
    fun inline(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        val plain = StringBuilder()
        var i = 0
        fun flushPlain() {
            if (plain.isNotEmpty()) {
                spans += Span(plain.toString(), SpanKind.Plain)
                plain.setLength(0)
            }
        }
        while (i < text.length) {
            val rest = text.substring(i)
            val fenced = rest.startsWith("```")
            val code = !fenced && rest.startsWith("`")
            val bold = rest.startsWith("**")
            val italic = !bold && rest.startsWith("*")
            if (code) {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    flushPlain()
                    spans += Span(text.substring(i + 1, end), SpanKind.Code)
                    i = end + 1
                    continue
                }
            }
            if (bold) {
                val end = text.indexOf("**", i + 2)
                if (end > i + 1) {
                    flushPlain()
                    spans += Span(text.substring(i + 2, end), SpanKind.Bold)
                    i = end + 2
                    continue
                }
            }
            if (italic) {
                val end = text.indexOf('*', i + 1)
                if (end > i && text[end - 1] != '*') {
                    flushPlain()
                    spans += Span(text.substring(i + 1, end), SpanKind.Italic)
                    i = end + 1
                    continue
                }
            }
            plain.append(text[i])
            i++
        }
        flushPlain()
        return spans
    }

    /**
     * Style one whole answer line that is not inside a fence. A heading wins over a bullet, a
     * bullet styles only its marker, and anything else falls through to inline parsing.
     */
    fun line(line: String): List<Span> {
        headingLevel(line)?.let { level ->
            // The hashes are dropped, not dimmed: a rendered heading shows styled words,
            // never the source prefix. Inline emphasis inside the heading still applies.
            return inline(line.trimStart().removePrefix("#".repeat(level) + " ")).map {
                if (it.kind == SpanKind.Plain) it.copy(kind = SpanKind.Heading) else it
            }
        }
        bulletMarker(line)?.let { marker ->
            return listOf(Span(marker, SpanKind.Marker)) + inline(line.substring(marker.length))
        }
        return inline(line)
    }
}

/**
 * Turns a stream of answer chunks into styled lines. Styling needs a whole line, so a chunk is
 * held until its newline arrives: [write] emits only complete lines and [flush] releases the
 * trailing partial one, which is how a terminal-style renderer can format live without ever
 * guessing at a marker that has not streamed in yet.
 */
class MarkdownStream(private val emit: (String, SpanKind) -> Unit) {

    private val pending = StringBuilder()
    private var inFence = false

    /**
     * Whether any content has reached the transcript yet. Until it has, blank lines that open an
     * answer are dropped: a reasoning model closes its thinking and starts the reply with
     * newlines, and those would otherwise sit as empty rows right after the label. Once a line
     * that carries text has gone out, a blank line carries meaning again and is kept.
     */
    private var started = false

    fun write(chunk: String) {
        for (ch in chunk) {
            if (ch != '\n') {
                pending.append(ch)
                continue
            }
            val line = pending.toString()
            pending.setLength(0)
            if (!started && line.isBlank()) continue
            started = true
            if (emitLine(line)) emit("\n", SpanKind.Plain)
        }
    }

    /**
     * Release whatever is held, so a turn that ends without a newline still shows its last line.
     * A tail made only of blanks, on a turn that never carried content, is dropped the way a
     * leading blank line is.
     */
    fun flush() {
        if (pending.isEmpty()) return
        val line = pending.toString()
        pending.setLength(0)
        if (started || line.isNotBlank()) {
            started = true
            emitLine(line)
        }
    }

    /** Style one line, and report whether anything visible was drawn so the caller can skip its newline. */
    private fun emitLine(line: String): Boolean {
        if (Markdown.isFence(line)) {
            val opening = !inFence
            inFence = !inFence
            // The fence characters are hidden so the block reads as a block, not as source.
            // An opening tag names the language; a closing line shows nothing.
            if (!opening) return false
            val language = Markdown.fenceLanguage(line) ?: return false
            emit(language, SpanKind.Marker)
            return true
        }
        if (inFence) {
            emit(line, SpanKind.CodeBlock)
            return true
        }
        Markdown.line(line).forEach { (text, kind) -> emit(text, kind) }
        return true
    }
}
