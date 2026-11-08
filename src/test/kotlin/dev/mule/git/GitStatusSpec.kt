package dev.mule.git

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * The tree colour, tooltip and ghost-row logic all hinge on one pure function: turning a
 * porcelain status report into the coarse per-path buckets. No subprocess here, so every
 * porcelain shape the real git can emit -- staged, unstaged, untracked, rename, spaced
 * name, quoted name, malformed -- is fed in as text and checked for the bucket it lands in.
 */
class GitStatusSpec : BehaviorSpec({

    given("an unstaged modification") {
        val m = GitStatus.parsePorcelain(" M README.md")
        then("the path maps to Modified") { m["README.md"] shouldBe FileChange.Modified }
    }

    given("a staged addition") {
        val m = GitStatus.parsePorcelain("A  src/A.kt")
        then("the path maps to Added") { m["src/A.kt"] shouldBe FileChange.Added }
    }

    given("a tracked deletion") {
        val m = GitStatus.parsePorcelain(" D gone.txt")
        then("the path maps to Deleted") { m["gone.txt"] shouldBe FileChange.Deleted }
    }

    given("an untracked file") {
        val m = GitStatus.parsePorcelain("?? new.txt")
        then("the path maps to Untracked") { m["new.txt"] shouldBe FileChange.Untracked }
    }

    given("a rename, which porcelain writes as old -> new") {
        val m = GitStatus.parsePorcelain("R  old.txt -> new.txt")
        then("it is stored under the new path as Renamed") { m["new.txt"] shouldBe FileChange.Renamed }
        then("the old path is dropped") { m.containsKey("old.txt") shouldBe false }
    }

    given("a path containing a space") {
        val m = GitStatus.parsePorcelain(" M my file.txt")
        then("the whole name is kept") { m["my file.txt"] shouldBe FileChange.Modified }
    }

    given("a path git wrapped in double quotes") {
        val m = GitStatus.parsePorcelain(" M \"weird name.txt\"")
        then("the quotes are stripped") { m["weird name.txt"] shouldBe FileChange.Modified }
    }

    given("a line too short to hold a status and a path") {
        then("it is ignored") { GitStatus.parsePorcelain("XY").isEmpty() shouldBe true }
    }

    given("a folder that is not a repository") {
        then("no path is ever reported as changed") { GitStatus.NOT_A_REPO.changeOf("README.md") shouldBe null }
    }

    given("deletions at the root and nested in folders") {
        val status = GitStatus(true, GitStatus.parsePorcelain(" D top.txt\n D src/a.kt\n D src/deep/b.kt"))
        then("only direct children of the src folder are listed") {
            status.deletedUnder("src") shouldContainExactly listOf("a.kt")
        }
        then("the root listing holds only the top-level deletion") {
            status.deletedUnder("") shouldContainExactly listOf("top.txt")
        }
    }
})
