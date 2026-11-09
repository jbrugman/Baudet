package dev.baudet.ui

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * The About body is curated markdown that must render as real HTML: styled spans, working
 * links, and no leftover markers or raw markup from characters that happen to look like HTML.
 */
class MarkdownHtmlSpec : BehaviorSpec({

    given("an inline span") {
        `when`("a run is bold") {
            then("it becomes strong") {
                MarkdownHtml.render("**Baudet**") shouldContain "<strong>Baudet</strong>"
            }
        }
        `when`("a run is italic") {
            then("it becomes em") {
                MarkdownHtml.render("a *baudet* brays") shouldContain "<em>baudet</em>"
            }
        }
        `when`("a run is code") {
            then("it becomes a code element") {
                MarkdownHtml.render("use `max_output_tokens`") shouldContain "<code>max_output_tokens</code>"
            }
        }
    }

    given("a link") {
        `when`("text points at a url") {
            then("it becomes an anchor carrying the url verbatim") {
                val html = MarkdownHtml.render("[oMLX](https://github.com/jundot/omlx)")
                html shouldContain "<a href=\"https://github.com/jundot/omlx\">oMLX</a>"
            }
        }
    }

    given("a block document") {
        `when`("two lines are separated by a blank line") {
            then("they become two paragraphs") {
                val html = MarkdownHtml.render("one\n\ntwo")
                html shouldContain "<p>one</p>"
                html shouldContain "<p>two</p>"
            }
        }
        `when`("consecutive lines start with a hyphen") {
            then("they collapse into one list") {
                val html = MarkdownHtml.render("- alpha\n- beta")
                html shouldContain "<ul>"
                html shouldContain "<li>alpha</li>"
                html shouldContain "<li>beta</li>"
            }
        }
        `when`("a paragraph follows the list") {
            then("the list closes before the paragraph opens") {
                val html = MarkdownHtml.render("- alpha\n\ntrailing")
                html shouldContain "</ul>"
                html shouldContain "<p>trailing</p>"
            }
        }
    }

    given("characters that look like markup") {
        `when`("the text holds an angle bracket") {
            then("it is escaped, never emitted as raw markup") {
                val html = MarkdownHtml.render("<script>oops</script>")
                html shouldNotContain "<script>"
                html shouldContain "&lt;script&gt;"
            }
        }
    }

    given("the full about body") {
        val body = buildString {
            appendLine("**Baudet** is a local-first assistant ([oMLX](https://github.com/jundot/omlx)).")
            appendLine()
            append("- **one**: first\n- two: second")
        }
        `when`("rendered") {
            then("the strong span and the link survive, and no markers leak through") {
                val html = MarkdownHtml.render(body)
                html shouldContain "<strong>Baudet</strong>"
                html shouldContain "<a href=\"https://github.com/jundot/omlx\">oMLX</a>"
                html shouldContain "<li><strong>one</strong>: first</li>"
                html shouldNotContain "**"
            }
        }
    }
})
