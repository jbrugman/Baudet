package dev.baudet.tool

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * Read is the missing half of the edit loop: a model that cannot see a file guesses
 * its bytes and its patch never lands. These drive the real tool against a temp folder
 * to pin the guarantees that make an edit possible at all -- the file is handed back
 * verbatim, the path stays inside the folder, and anything too big or not text is
 * refused rather than dumped into the context.
 */
class ReadFileToolSpec : BehaviorSpec({
    val roots = mutableListOf<Path>()

    afterProject { roots.forEach { runCatching { it.toFile().deleteRecursively() } } }

    fun newRoot(name: String): Path {
        val dir = Files.createTempDirectory(name)
        roots.add(dir)
        return dir
    }

    fun args(file: String): JsonObject = buildJsonObject { put("file", file) }

    val tool = ReadFileTool()

    given("a folder with a readable file") {
        val root = newRoot("baudet-read")
        val sub = Files.createDirectories(root.resolve("sub"))
        val target = sub.resolve("a.txt")
        Files.writeString(target, "one\ntwo\nthree\n")
        val ctx = ToolContext(root, writeGranted = true, approve = { _, _ -> true })
        `when`("reading it") {
            val result = tool.execute(args("sub/a.txt"), ctx)
            then("the current contents come back verbatim") {
                result.ok shouldBe true
                result.output.contains("one\ntwo\nthree") shouldBe true
                result.output.contains("sub/a.txt") shouldBe true
            }
        }
    }

    given("no folder is linked") {
        val ctx = ToolContext(null, writeGranted = true, approve = { _, _ -> true })
        `when`("reading anything") {
            val result = tool.execute(args("a.txt"), ctx)
            then("it refuses because there is nothing to read from") {
                result.ok shouldBe false
                result.output.contains("no project folder") shouldBe true
            }
        }
    }

    given("a path that escapes the folder") {
        val root = newRoot("baudet-read-escape")
        val ctx = ToolContext(root, writeGranted = true, approve = { _, _ -> true })
        `when`("resolving ../secret.txt") {
            val result = tool.execute(args("../secret.txt"), ctx)
            then("it refuses an outside path") {
                result.ok shouldBe false
                result.output.contains("outside") shouldBe true
            }
        }
    }

    given("a file that is not there") {
        val root = newRoot("baudet-read-missing")
        Files.writeString(root.resolve("present.txt"), "keep\n")
        val ctx = ToolContext(root, writeGranted = true, approve = { _, _ -> true })
        `when`("reading a path that does not exist") {
            val result = tool.execute(args("nope.txt"), ctx)
            then("it fails and names the real files so the model can correct the path") {
                result.ok shouldBe false
                result.output.contains("does not exist") shouldBe true
                result.output.contains("present.txt") shouldBe true
            }
        }
    }

    given("an empty file") {
        val root = newRoot("baudet-read-empty")
        Files.writeString(root.resolve("a.txt"), "")
        val ctx = ToolContext(root, writeGranted = true, approve = { _, _ -> true })
        `when`("reading it") {
            val result = tool.execute(args("a.txt"), ctx)
            then("it succeeds and says so plainly") {
                result.ok shouldBe true
                result.output.contains("empty") shouldBe true
            }
        }
    }

    given("a binary file") {
        val root = newRoot("baudet-read-binary")
        Files.write(root.resolve("blob.bin"), byteArrayOf(1, 2, 0, 3, 4))
        val ctx = ToolContext(root, writeGranted = true, approve = { _, _ -> true })
        `when`("reading it") {
            val result = tool.execute(args("blob.bin"), ctx)
            then("it refuses without dumping bytes") {
                result.ok shouldBe false
                result.output.contains("binary") shouldBe true
            }
        }
    }

    given("a file over the byte limit") {
        val root = newRoot("baudet-read-huge")
        Files.write(root.resolve("big.bin"), ByteArray(1_500_000) { 'a'.code.toByte() })
        val ctx = ToolContext(root, writeGranted = true, approve = { _, _ -> true })
        `when`("reading it") {
            val result = tool.execute(args("big.bin"), ctx)
            then("it refuses for size rather than loading it whole") {
                result.ok shouldBe false
                result.output.contains("limit") shouldBe true
            }
        }
    }

    given("a text file over the character cap but under the byte limit") {
        val root = newRoot("baudet-read-trunc")
        Files.writeString(root.resolve("a.txt"), "a".repeat(70_000))
        val ctx = ToolContext(root, writeGranted = true, approve = { _, _ -> true })
        `when`("reading it") {
            val result = tool.execute(args("a.txt"), ctx)
            then("it succeeds but flags the truncation so the model knows it is partial") {
                result.ok shouldBe true
                result.output.contains("60000 of 70000") shouldBe true
                (result.output.length < 70_000) shouldBe true
            }
        }
    }
})
