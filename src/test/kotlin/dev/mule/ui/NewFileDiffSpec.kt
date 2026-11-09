package dev.mule.ui

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * The new-file renderer is the reason a brand-new file can be inspected at all: git gives
 * no patch for it, so these scenarios prove the intro message survives, that real bytes
 * become a proper all-added diff, and that anything unsafe, a null read, an empty file, a
 * binary body or a file past the byte cap, falls back to the plain message rather than
 * dumping noise into the viewer. [NewFileDiff.plain] covers the other no-patch case, an
 * unchanged file: the bare text, still refusing anything unsafe.
 */
class NewFileDiffSpec : BehaviorSpec({
    val rel = "sub/new.txt"
    val intro = "new and untracked"

    given("no bytes were read") {
        `when`("rendering") {
            then("only the intro is shown") { NewFileDiff.body(rel, intro, null) shouldBe intro }
        }
    }

    given("the file is empty") {
        `when`("rendering") {
            then("only the intro is shown") { NewFileDiff.body(rel, intro, ByteArray(0)) shouldBe intro }
        }
    }

    given("the file looks binary") {
        val bytes = byteArrayOf(1, 2, 0, 3)
        `when`("rendering") {
            then("only the intro is shown") { NewFileDiff.body(rel, intro, bytes) shouldBe intro }
        }
    }

    given("the file is larger than the byte cap") {
        val bytes = ByteArray(NewFileDiff.MAX_BYTES + 1) { 'a'.code.toByte() }
        `when`("rendering") {
            then("only the intro is shown") { NewFileDiff.body(rel, intro, bytes) shouldBe intro }
        }
    }

    given("a small text file") {
        val bytes = "alpha\nbeta\n".toByteArray(Charsets.UTF_8)
        val body = NewFileDiff.body(rel, intro, bytes)
        `when`("rendering") {
            then("the intro is kept and an added-file header is added") {
                body shouldContain intro
                body shouldContain "+++ b/$rel"
            }
            then("every content line is prefixed as an addition") {
                body shouldContain "+alpha"
                body shouldContain "+beta"
                body shouldNotContain "(content truncated)"
            }
        }
    }

    given("a text file past the character cap") {
        val bytes = "a".repeat(NewFileDiff.MAX_CHARS + 10).toByteArray(Charsets.UTF_8)
        val body = NewFileDiff.body(rel, intro, bytes)
        `when`("rendering") {
            then("the content is truncated with a marker") {
                body shouldContain "(content truncated)"
                body shouldContain intro
            }
        }
    }

    given("an unchanged file shown as plain text") {
        val bytes = "alpha\nbeta\n".toByteArray(Charsets.UTF_8)
        `when`("rendering") {
            then("the text is handed back without any diff prefix") {
                NewFileDiff.plain(bytes) shouldBe "alpha\nbeta\n"
            }
            then("nothing was added to it") {
                NewFileDiff.plain(bytes) shouldNotContain "+"
                NewFileDiff.plain(bytes) shouldNotContain intro
            }
        }
    }

    given("a content line that looks like a diff line") {
        val bytes = "- removed lookalike\n+ added lookalike\n".toByteArray(Charsets.UTF_8)
        `when`("rendering as plain") {
            then("the line is kept verbatim, so the viewer cannot color it as a change") {
                NewFileDiff.plain(bytes) shouldBe "- removed lookalike\n+ added lookalike\n"
            }
        }
    }

    given("plain text from unsafe input") {
        `when`("rendering") {
            then("a null read yields nothing to show") { NewFileDiff.plain(null) shouldBe null }
            then("an empty file yields nothing to show") { NewFileDiff.plain(ByteArray(0)) shouldBe null }
            then("a binary body yields nothing to show") {
                NewFileDiff.plain(byteArrayOf(1, 2, 0, 3)) shouldBe null
            }
            then("a file past the byte cap yields nothing to show") {
                NewFileDiff.plain(ByteArray(NewFileDiff.MAX_BYTES + 1) { 'a'.code.toByte() }) shouldBe null
            }
        }
    }

    given("plain text past the character cap") {
        val bytes = "a".repeat(NewFileDiff.MAX_CHARS + 10).toByteArray(Charsets.UTF_8)
        `when`("rendering") {
            then("the content is truncated with a marker") {
                val plain = NewFileDiff.plain(bytes)!!
                plain shouldContain "(content truncated)"
                plain.length shouldNotBe NewFileDiff.MAX_CHARS + 10
            }
        }
    }
})
