package dev.mule.tool

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * The tool is the security boundary of the whole feature: it is where Write is
 * actually enforced, where a path is kept inside the folder, where a preview is
 * asked before any byte is written, and where the original is kept so a bad edit
 * can be undone. Each scenario drives the real tool against a temp folder so those
 * guarantees are proven, not asserted in a comment.
 */
class EditFileToolSpec : BehaviorSpec({
    val roots = mutableListOf<Path>()

    afterProject { roots.forEach { runCatching { it.toFile().deleteRecursively() } } }

    fun newRoot(name: String): Path {
        val dir = Files.createTempDirectory(name)
        roots.add(dir)
        return dir
    }

    fun args(file: String, vararg e: Triple<String, String, Boolean>): JsonObject =
        buildJsonObject {
            put("file", file)
            put("edits", buildJsonArray {
                e.forEach { (o, n, all) ->
                    addJsonObject {
                        put("old_text", o)
                        put("new_text", n)
                        if (all) put("replace_all", true)
                    }
                }
            })
        }

    val tool = EditFileTool()

    given("a folder with a file and write granted") {
        val root = newRoot("mule-edit")
        val sub = Files.createDirectories(root.resolve("sub"))
        val target = sub.resolve("a.txt")
        Files.writeString(target, "one\ntwo\nthree\n")
        val ctx = ToolContext(root, writeGranted = true, approve = { _, _ -> true })

        `when`("applying an approved edit") {
            val result = tool.execute(args("sub/a.txt", Triple("two", "TWO", false)), ctx)
            then("it succeeds and the file on disk carries the change") {
                result.ok shouldBe true
                Files.readString(target) shouldBe "one\nTWO\nthree\n"
            }
            then("the result offers a diff and an undo") {
                (result.diff != null) shouldBe true
                result.diff!!.contains("+TWO") shouldBe true
                result.undo!!()
                Files.readString(target) shouldBe "one\ntwo\nthree\n"
            }
        }
    }

    given("write is not granted") {
        val root = newRoot("mule-edit-nowrite")
        Files.writeString(root.resolve("a.txt"), "x\n")
        val ctx = ToolContext(root, writeGranted = false, approve = { _, _ -> true })
        `when`("editing anyway") {
            val result = tool.execute(args("a.txt", Triple("x", "y", false)), ctx)
            then("it refuses and does not touch the file") {
                result.ok shouldBe false
                result.output.contains("write access") shouldBe true
                Files.readString(root.resolve("a.txt")) shouldBe "x\n"
            }
        }
    }

    given("a path that escapes the folder") {
        val root = newRoot("mule-edit-escape")
        val ctx = ToolContext(root, writeGranted = true, approve = { _, _ -> true })
        `when`("resolving ../escape.txt") {
            val result = tool.execute(args("../escape.txt", Triple("a", "b", false)), ctx)
            then("it refuses an outside path") {
                result.ok shouldBe false
                result.output.contains("outside") shouldBe true
            }
        }
    }

    given("an ambiguous match") {
        val root = newRoot("mule-edit-ambig")
        Files.writeString(root.resolve("a.txt"), "x\nx\n")
        val ctx = ToolContext(root, writeGranted = true, approve = { _, _ -> true })
        `when`("replacing a line that matches twice") {
            val result = tool.execute(args("a.txt", Triple("x", "y", false)), ctx)
            then("it refuses with the count and leaves the file") {
                result.ok shouldBe false
                result.output.contains("matches 2 times") shouldBe true
                Files.readString(root.resolve("a.txt")) shouldBe "x\nx\n"
            }
        }
    }

    given("the user rejects the preview") {
        val root = newRoot("mule-edit-deny")
        val target = root.resolve("a.txt")
        Files.writeString(target, "one\n")
        val ctx = ToolContext(root, writeGranted = true, approve = { _, _ -> false })
        `when`("editing") {
            val result = tool.execute(args("a.txt", Triple("one", "two", false)), ctx)
            then("nothing is written") {
                result.ok shouldBe false
                result.output.contains("declined") shouldBe true
                Files.readString(target) shouldBe "one\n"
            }
        }
    }

    given("new_text equals old_text") {
        val root = newRoot("mule-edit-noop")
        Files.writeString(root.resolve("a.txt"), "keep\n")
        val ctx = ToolContext(root, writeGranted = true, approve = { _, _ -> true })
        `when`("applying a no-change edit") {
            val result = tool.execute(args("a.txt", Triple("keep", "keep", false)), ctx)
            then("it reports no change without a diff or approval") {
                result.ok shouldBe true
                result.output.contains("no change") shouldBe true
                (result.diff == null) shouldBe true
            }
        }
    }

    given("a file that is not there") {
        val root = newRoot("mule-edit-missing")
        Files.writeString(root.resolve("present.txt"), "keep\n")
        val sub = Files.createDirectories(root.resolve("sub"))
        Files.writeString(sub.resolve("inner.txt"), "keep\n")
        val ctx = ToolContext(root, writeGranted = true, approve = { _, _ -> true })
        `when`("editing a path that does not exist") {
            val result = tool.execute(args("nope.txt", Triple("a", "b", false)), ctx)
            then("it fails and names the real files so the model can correct the path") {
                result.ok shouldBe false
                (result.output.contains("does not exist")) shouldBe true
                (result.output.contains("present.txt")) shouldBe true
                (result.output.contains("sub/inner.txt")) shouldBe true
            }
        }
    }

    given("a file replaced by a symlink to outside while the preview is approved") {
        val root = newRoot("mule-edit-symlink")
        val target = root.resolve("a.txt")
        val outsideDir = newRoot("mule-edit-symlink-out")
        val outside = outsideDir.resolve("evil.txt")
        val probe = newRoot("mule-edit-symlink-probe")
        val supportsLinks = runCatching {
            Files.writeString(probe.resolve("f"), "f")
            Files.createSymbolicLink(probe.resolve("l"), probe.resolve("f"))
        }.isSuccess
        Files.writeString(target, "one\n")
        Files.writeString(outside, "one\n")
        val ctx = ToolContext(
            root,
            writeGranted = true,
            approve = { _, _ ->
                runCatching {
                    Files.delete(target)
                    Files.createSymbolicLink(target, outside)
                }
                true
            }
        )
        `when`("approval turns the file into a link toward the outside file") {
            then("the boundary holds at the write and the outside file is left intact") {
                // Symbolic links are not permitted everywhere (e.g. Windows CI); skip there.
                if (!supportsLinks) {
                    true shouldBe true
                } else {
                    val result = tool.execute(args("a.txt", Triple("one", "two", false)), ctx)
                    result.ok shouldBe false
                    Files.readString(outside) shouldBe "one\n"
                }
            }
        }
    }

})
