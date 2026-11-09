package dev.baudet.ui

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * Styling answer text is only an improvement while unmarked text survives untouched, so these
 * cases lean on the ways a coding answer can be misread: a stray asterisk in arithmetic, the
 * underscores of an identifier, and markers that belong to code rather than to prose.
 */
class MarkdownSpec : BehaviorSpec({

    fun kinds(spans: List<Span>) = spans.map { it.kind to it.text }

    given("answer text that carries no markers") {
        `when`("a plain sentence is parsed") {
            then("it comes back as one untouched run") {
                kinds(Markdown.inline("the divider caps the width")) shouldContainExactly
                    listOf(SpanKind.Plain to "the divider caps the width")
            }
        }
        `when`("arithmetic holds a lone asterisk") {
            then("nothing is turned into emphasis") {
                kinds(Markdown.inline("2 * 3 = 6")) shouldContainExactly
                    listOf(SpanKind.Plain to "2 * 3 = 6")
            }
        }
        `when`("arithmetic holds a pair of asterisks") {
            then("the multiplication stays literal, because a spaced marker is not emphasis") {
                kinds(Markdown.inline("2 * 3 * 4")) shouldContainExactly
                    listOf(SpanKind.Plain to "2 * 3 * 4")
            }
            then("a formula reads the same way") {
                kinds(Markdown.inline("a * b * c = 6")) shouldContainExactly
                    listOf(SpanKind.Plain to "a * b * c = 6")
            }
        }
        `when`("an identifier is written with underscores") {
            then("the name survives verbatim, which is why underscores are never emphasis") {
                kinds(Markdown.inline("set max_output_tokens to zero")) shouldContainExactly
                    listOf(SpanKind.Plain to "set max_output_tokens to zero")
            }
        }
    }

    given("markers that never close") {
        `when`("bold is opened but not closed") {
            then("the asterisks stay on screen instead of styling the rest of the turn") {
                kinds(Markdown.inline("a ** b")) shouldContainExactly
                    listOf(SpanKind.Plain to "a ** b")
            }
        }
        `when`("a backtick is left open") {
            then("the sentence is not swallowed as code") {
                kinds(Markdown.inline("use ` for code")) shouldContainExactly
                    listOf(SpanKind.Plain to "use ` for code")
            }
        }
    }

    given("markers that pad themselves with spaces") {
        `when`("an italic pair is opened and closed on a space") {
            then("it stays literal, because emphasis hugs the words it styles") {
                kinds(Markdown.inline("a * b * c")) shouldContainExactly
                    listOf(SpanKind.Plain to "a * b * c")
            }
        }
        `when`("a bold pair is padded the same way") {
            then("it is not styled either") {
                kinds(Markdown.inline("a ** b ** c")) shouldContainExactly
                    listOf(SpanKind.Plain to "a ** b ** c")
            }
        }
        `when`("only one side is padded") {
            then("the pair is still refused, since both sides have to hold") {
                kinds(Markdown.inline("*draft *note")) shouldContainExactly
                    listOf(SpanKind.Plain to "*draft *note")
            }
        }
    }

    given("markers that do close") {
        `when`("bold, italic and inline code share a sentence") {
            then("each run carries the kind it asked for") {
                kinds(Markdown.inline("a **b** c *d* e `f`")) shouldContainExactly listOf(
                    SpanKind.Plain to "a ",
                    SpanKind.Bold to "b",
                    SpanKind.Plain to " c ",
                    SpanKind.Italic to "d",
                    SpanKind.Plain to " e ",
                    SpanKind.Code to "f"
                )
            }
        }
        `when`("a closed pair is followed by a stray asterisk") {
            then("the emphasis is taken and the extra marker is kept") {
                kinds(Markdown.inline("*foo**")) shouldContainExactly listOf(
                    SpanKind.Italic to "foo",
                    SpanKind.Plain to "*"
                )
            }
        }
        `when`("a multiplication sits next to real emphasis") {
            then("only the emphasis is styled") {
                kinds(Markdown.inline("width * 2 and *note*")) shouldContainExactly listOf(
                    SpanKind.Plain to "width * 2 and ",
                    SpanKind.Italic to "note"
                )
            }
        }
    }

    given("a line that opens with structure") {
        `when`("it is a heading") {
            then("the hashes are dropped and the words are a heading") {
                kinds(Markdown.line("## Title")) shouldContainExactly listOf(
                    SpanKind.Heading to "Title"
                )
            }
        }
        `when`("it is a bullet") {
            then("only the marker is set apart from the text") {
                kinds(Markdown.line("- item")) shouldContainExactly listOf(
                    SpanKind.Marker to "- ",
                    SpanKind.Plain to "item"
                )
            }
        }
        `when`("the level of a heading is asked for") {
            then("six hashes still count and seven do not") {
                Markdown.headingLevel("###### deep") shouldBe 6
                Markdown.headingLevel("####### too deep") shouldBe null
            }
        }
    }

    given("a fenced block streaming in") {
        // Collected per case on purpose: kotest re-runs the enclosing body for every leaf, so a
        // stream must never be shared across cases and each one feeds its own.
        val emitted = mutableListOf<Pair<SpanKind, String>>()
        MarkdownStream { text, kind -> emitted += kind to text }
            .write("```kotlin\nlet x = *y*\n```\nplain *a*\n")

        `when`("the block arrives line by line") {
            then("the fence characters are hidden and only the language tag shows") {
                emitted[0] shouldBe (SpanKind.Marker to "kotlin")
                emitted.none { (kind, text) -> kind == SpanKind.CodeBlock && text.startsWith("```") } shouldBe true
            }
            then("the lines inside are never inline parsed, so that asterisk is code") {
                emitted[2] shouldBe (SpanKind.CodeBlock to "let x = *y*")
            }
            then("the closing fence leaves nothing behind") {
                emitted.none { (_, text) -> text == "```" } shouldBe true
            }
            then("line breaks are emitted as plain text between the styled runs") {
                emitted[1] shouldBe (SpanKind.Plain to "\n")
            }
            then("parsing resumes once the block closes") {
                emitted.subList(4, 6) shouldContainExactly listOf(
                    SpanKind.Plain to "plain ",
                    SpanKind.Italic to "a"
                )
            }
            then("a fence without a tag leaves no marker and no blank line") {
                val tagless = mutableListOf<Pair<SpanKind, String>>()
                MarkdownStream { text, kind -> tagless += kind to text }.write("```\ncode\n```\n")
                tagless shouldContainExactly listOf(
                    SpanKind.CodeBlock to "code",
                    SpanKind.Plain to "\n"
                )
            }
        }
    }

    given("an answer that is only half received") {
        val emitted = mutableListOf<Pair<SpanKind, String>>()
        MarkdownStream { text, kind -> emitted += kind to text }.write("**bo")

        `when`("a line is still open") {
            then("nothing is emitted, because the marker might still close") {
                emitted.shouldBeEmpty()
            }
        }
    }

    given("a marker that closes in a later chunk") {
        val emitted = mutableListOf<Pair<SpanKind, String>>()
        val stream = MarkdownStream { text, kind -> emitted += kind to text }
        stream.write("**bo")
        stream.write("ld**\n")

        `when`("the whole line has arrived across chunks") {
            then("it goes out styled, as one run plus its break") {
                emitted shouldContainExactly listOf(
                    SpanKind.Bold to "bold",
                    SpanKind.Plain to "\n"
                )
            }
        }
    }

    given("a turn that ends without a trailing newline") {
        val emitted = mutableListOf<Pair<SpanKind, String>>()
        val stream = MarkdownStream { text, kind -> emitted += kind to text }
        stream.write("tail")

        `when`("flush releases the held tail") {
            then("the last line is not dropped") {
                stream.flush()
                emitted shouldContainExactly listOf(SpanKind.Plain to "tail")
            }
            then("flushing a second time emits nothing again") {
                stream.flush()
                emitted.size shouldBe 1
            }
        }
    }

    given("an answer that opens with blank lines") {
        val emitted = mutableListOf<Pair<SpanKind, String>>()
        MarkdownStream { text, kind -> emitted += kind to text }.write("\n\nHello\n")

        `when`("the leading blank lines are dropped") {
            then("the first emission is the text line, with no break before it") {
                emitted shouldContainExactly listOf(
                    SpanKind.Plain to "Hello",
                    SpanKind.Plain to "\n"
                )
            }
        }
    }

    given("an answer that drops its leading blank but keeps its inner ones") {
        val emitted = mutableListOf<Pair<SpanKind, String>>()
        MarkdownStream { text, kind -> emitted += kind to text }.write("\nA\n\nB\n")

        `when`("it is streamed whole") {
            then("only the leading break is gone, the gap between the two lines is kept") {
                emitted shouldContainExactly listOf(
                    SpanKind.Plain to "A",
                    SpanKind.Plain to "\n",
                    SpanKind.Plain to "\n",
                    SpanKind.Plain to "B",
                    SpanKind.Plain to "\n"
                )
            }
        }
    }

    given("an answer that is nothing but blank lines") {
        val emitted = mutableListOf<Pair<SpanKind, String>>()
        val stream = MarkdownStream { text, kind -> emitted += kind to text }
        stream.write("\n\n")

        `when`("flush releases the tail") {
            then("nothing is emitted, because there was never any content") {
                stream.flush()
                emitted.shouldBeEmpty()
            }
        }
    }
})
