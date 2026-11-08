package dev.mule.git

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * [GitInspector] talks to the real git command line, which the pure parser spec cannot reach.
 * This is the regression guard for the bug where a bad argument made every status call fail
 * closed: it builds a throwaway repository with one modified file, one deleted file and one
 * untracked file, then asserts the shell-out classifies each and returns their patch. It also
 * checks that a folder which is not a repository is reported as such, not as an empty repo.
 */
class GitInspectorSpec : BehaviorSpec({

    val created = mutableListOf<Path>()

    fun freshDir(): Path = Files.createTempDirectory("mule-git").also { created.add(it) }

    afterProject { created.forEach { runCatching { it.toFile().deleteRecursively() } } }

    given("a fresh repository with a modification, a deletion and an untracked file") {
        val root = freshDir()
        git(root, "init", "-q", "-b", "main")
        git(root, "config", "user.email", "mule@example.com")
        git(root, "config", "user.name", "Mule Test")
        Files.writeString(root.resolve("tracked.txt"), "one\n")
        Files.writeString(root.resolve("removed.txt"), "gone\n")
        git(root, "-c", "commit.gpgsign=false", "add", ".")
        git(root, "-c", "commit.gpgsign=false", "commit", "-q", "-m", "initial")

        Files.writeString(root.resolve("tracked.txt"), "two\n")   // modified, unstaged
        Files.writeString(root.resolve("new.txt"), "brand new\n") // untracked
        Files.delete(root.resolve("removed.txt"))                 // deleted, tracked

        val inspector = GitInspector()

        `when`("reading the status") {
            val st = inspector.status(root)
            then("it recognizes the repository") { st.isRepository shouldBe true }
            then("a changed tracked file is Modified") { st.changeOf("tracked.txt") shouldBe FileChange.Modified }
            then("a deleted tracked file is Deleted") { st.changeOf("removed.txt") shouldBe FileChange.Deleted }
            then("a brand new file is Untracked") { st.changeOf("new.txt") shouldBe FileChange.Untracked }
        }

        `when`("reading a patch") {
            then("a modified tracked file yields a non-empty diff") {
                inspector.diffPatch(root, "tracked.txt").isEmpty() shouldBe false
            }
            then("a brand new untracked file yields no diff") {
                inspector.diffPatch(root, "new.txt").isEmpty() shouldBe true
            }
        }
    }

    given("a folder that is not a repository") {
        val root = freshDir()
        `when`("reading the status") {
            then("it reports not a repository") { GitInspector().status(root).isRepository shouldBe false }
        }
    }
})

/** Runs git in [dir], throwing with the combined output when it fails; keeps the test honest. */
private fun git(dir: Path, vararg args: String): String {
    val process = ProcessBuilder(listOf("git", "-C", dir.toString()) + args)
        .redirectErrorStream(true)
        .start()
    process.outputStream.close()
    val out = process.inputStream.bufferedReader().readText()
    val code = process.waitFor()
    if (code != 0) error("git ${args.joinToString(" ")} exited $code: $out")
    return out
}
