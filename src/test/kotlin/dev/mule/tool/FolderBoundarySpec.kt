package dev.mule.tool

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * The boundary is the security line for both file tools: a path may sit inside the folder
 * lexically yet reach outside through a symbolic link. These drive real temp folders and a
 * real link to pin that what matters is the resolved target, not the literal path.
 */
class FolderBoundarySpec : BehaviorSpec({
    val roots = mutableListOf<Path>()

    afterProject { roots.forEach { runCatching { it.toFile().deleteRecursively() } } }

    fun tmp(name: String): Path = Files.createTempDirectory(name).also { roots.add(it) }

    given("a folder holding a real file") {
        val root = tmp("mule-boundary-in")
        val inner = root.resolve("a.txt")
        Files.writeString(inner, "x")
        `when`("a path inside the folder is checked") {
            val real = FolderBoundary.realWithin(root, inner.normalize())
            then("it resolves to the real path that stays inside") {
                (real != null) shouldBe true
                (real!!.startsWith(root.toRealPath())) shouldBe true
            }
        }
    }

    given("a path that lands outside the folder") {
        val root = tmp("mule-boundary-out")
        val outside = tmp("mule-boundary-secret").resolve("secret.txt")
        Files.writeString(outside, "y")
        `when`("it is checked against the folder") {
            then("it is refused, so the tool never touches it") {
                (FolderBoundary.realWithin(root, outside) == null) shouldBe true
            }
        }
    }

    given("a symbolic link inside the folder pointing outside") {
        val root = tmp("mule-boundary-link")
        val target = tmp("mule-boundary-target").resolve("secret.txt")
        Files.writeString(target, "z")
        val link = root.resolve("escape.txt")
        val linked = runCatching { Files.createSymbolicLink(link, target) }.isSuccess
        `when`("the link is checked against the folder") {
            then("the escaped target is refused once links are followed") {
                // Link creation is not permitted everywhere (e.g. Windows CI); skip there.
                if (linked) (FolderBoundary.realWithin(root, link) == null) shouldBe true
                else true shouldBe true
            }
        }
    }
})
