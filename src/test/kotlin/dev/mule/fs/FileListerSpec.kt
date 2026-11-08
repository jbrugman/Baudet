package dev.mule.fs

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * The tree's whole ordering contract lives in FileLister, and the UI never reshuffles it,
 * so this is where that contract earns its keep: folders first, each group alphabetical and
 * case-insensitive, hidden entries behind a flag, and a missing folder read as empty rather
 * than thrown.
 */
class FileListerSpec : BehaviorSpec({
    val created = mutableListOf<Path>()

    afterProject { created.forEach { runCatching { it.toFile().deleteRecursively() } } }

    given("a project folder with files and subfolders") {
        val dir = Files.createTempDirectory("mule-tree")
        created.add(dir)
        Files.createFile(dir.resolve("beta.txt"))
        Files.createFile(dir.resolve("Alpha.md"))
        Files.createDirectories(dir.resolve("src"))
        Files.createFile(dir.resolve(".hidden"))

        `when`("listing it with the defaults") {
            val entries = FileLister.list(dir)
            then("folders come before files") {
                entries.first().isDirectory shouldBe true
                entries.first().name shouldBe "src"
            }
            then("each group is alphabetical and case-insensitive") {
                entries.map { it.name } shouldContainExactly listOf("src", "Alpha.md", "beta.txt")
            }
            then("hidden entries stay out") {
                entries.none { it.name == ".hidden" } shouldBe true
            }
        }

        `when`("listing it with hidden shown") {
            val entries = FileLister.list(dir, showHidden = true)
            then("the hidden entry appears in name order") {
                entries.map { it.name } shouldContainExactly listOf("src", ".hidden", "Alpha.md", "beta.txt")
            }
        }
    }

    given("a folder that is not there") {
        `when`("listing it") {
            then("the listing is empty rather than an error") {
                FileLister.list(Path.of("/no/such/mule/dir")).shouldBeEmpty()
            }
        }
    }

    given("an empty folder") {
        val dir = Files.createTempDirectory("mule-tree-empty")
        created.add(dir)
        `when`("listing it") {
            then("there is nothing to add") {
                FileLister.list(dir).shouldBeEmpty()
            }
        }
    }
})
