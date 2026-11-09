package dev.baudet.fs

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * The watcher's whole safety contract lives in the two set decisions it makes about what to watch:
 * which folders a walk may register, and how a watch is reconciled against what is on disk. Both
 * are pure and deterministic, so they earn their keep here: the walk must reach every nested folder,
 * prune .git with its subtree so a git command is never mistaken for a user change, honor the cap
 * that keeps the watch list off the operating-system limit, treat a folder that is not there as
 * nothing to watch, and the reconcile must add folders that appeared and cancel folders that vanished.
 *
 * The timing of the live watch is deliberately not asserted -- the set logic above is the part that
 * can be decided without waiting on the clock or a real file-system event.
 */
class FileWatcherSpec : BehaviorSpec({
    val created = mutableListOf<Path>()

    afterProject { created.forEach { runCatching { it.toFile().deleteRecursively() } } }

    given("a project folder with nested directories and a git directory") {
        val root = Files.createTempDirectory("baudet-watch")
        created.add(root)
        val a = Files.createDirectories(root.resolve("a"))
        val b = Files.createDirectories(a.resolve("b"))
        val git = Files.createDirectories(root.resolve(".git"))
        val objects = Files.createDirectories(git.resolve("objects"))
        Files.createFile(root.resolve("readme.txt"))

        `when`("collecting the watchable directories") {
            val dirs = FileWatcher.collectWatchableDirs(root).toSet()

            then("the root and every nested directory are watched") {
                dirs shouldBe setOf(root, a, b)
            }
            then("files themselves are never watched") {
                dirs.none { it.fileName.toString() == "readme.txt" } shouldBe true
            }
            then("the git directory is pruned along with its whole subtree") {
                dirs.contains(git) shouldBe false
                dirs.contains(objects) shouldBe false
            }
        }
    }

    given("a tree deeper than the cap") {
        val root = Files.createTempDirectory("baudet-watch-cap")
        created.add(root)
        Files.createDirectories(root.resolve("a"))
        Files.createDirectories(root.resolve("b"))
        Files.createDirectories(root.resolve("c"))

        `when`("collecting with room for only two directories") {
            val dirs = FileWatcher.collectWatchableDirs(root, max = 2)

            then("the walk stops at the cap rather than overrunning") {
                dirs shouldHaveSize 2
            }
            then("the root is still among them") {
                dirs.contains(root) shouldBe true
            }
        }
    }

    given("a folder that is not there") {
        `when`("collecting from it") {
            then("there is nothing to watch rather than an error") {
                FileWatcher.collectWatchableDirs(Path.of("/no/such/baudet/watch/dir")).shouldBeEmpty()
            }
        }
    }

    given("a watch reconciled against what is on disk") {
        val staying = Path.of("/baudet/src")
        val gone = Path.of("/baudet/old")
        val fresh = Path.of("/baudet/new")

        `when`("a directory vanished and another appeared") {
            val (toAdd, toCancel) = FileWatcher.planReconcile(
                watched = setOf(staying, gone),
                present = setOf(staying, fresh)
            )

            then("the folder that appeared is the only one to add") {
                toAdd shouldBe setOf(fresh)
            }
            then("the folder that vanished is the only one to cancel") {
                toCancel shouldBe setOf(gone)
            }
            then("the folder present on both sides is left untouched") {
                (staying in toAdd) shouldBe false
                (staying in toCancel) shouldBe false
            }
        }

        `when`("the watch already matches the disk exactly") {
            val (toAdd, toCancel) = FileWatcher.planReconcile(
                watched = setOf(staying, fresh),
                present = setOf(staying, fresh)
            )

            then("there is nothing to add and nothing to cancel") {
                toAdd.shouldBeEmpty()
                toCancel.shouldBeEmpty()
            }
        }
    }
})
