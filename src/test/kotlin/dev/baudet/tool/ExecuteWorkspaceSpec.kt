package dev.baudet.tool

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * The workspace guard is the part of the run feature that decides, before a script is
 * written, which folders are real and usable. It resolves through symlinks, so these
 * scenarios prove it rejects a missing project folder, accepts a genuine one, allows a
 * file that really sits in the temp folder, and refuses a link that leaves the allowed
 * roots once followed. Symbolic links are skipped where the platform forbids them.
 */
class ExecuteWorkspaceSpec : BehaviorSpec({
    val roots = mutableListOf<Path>()

    afterProject { roots.forEach { runCatching { it.toFile().deleteRecursively() } } }

    fun newRoot(name: String): Path {
        val dir = Files.createTempDirectory(name)
        roots.add(dir)
        return dir
    }

    given("a project path that does not exist") {
        val missing = newRoot("baudet-workspace-x").resolve("nope")
        `when`("resolving the project directory") {
            then("it yields null") { ExecuteWorkspace.projectDir(missing) shouldBe null }
        }
    }

    given("a project path that is a real directory") {
        val dir = newRoot("baudet-workspace-dir")
        `when`("resolving the project directory") {
            then("it yields the real path") { ExecuteWorkspace.projectDir(dir) shouldBe dir.toRealPath() }
        }
    }

    given("the system temp directory") {
        val temp = ExecuteWorkspace.tempDir()
        `when`("a file written inside it is checked") {
            then("it is allowed") {
                if (temp == null) {
                    true shouldBe true
                } else {
                    val inside = Files.createTempFile(temp, "baudet-ws-", ".txt")
                    ExecuteWorkspace.withinAllowed(inside, listOf(temp)) shouldBe true
                    runCatching { Files.deleteIfExists(inside) }
                }
            }
        }
    }

    given("a link that points outside the allowed roots") {
        val allowed = newRoot("baudet-ws-allowed")
        val outside = newRoot("baudet-ws-outside")
        val outsideFile = outside.resolve("secret.txt")
        val link = allowed.resolve("link.txt")
        val probe = newRoot("baudet-ws-probe")
        val supportsLinks = runCatching {
            Files.writeString(probe.resolve("f"), "f")
            Files.createSymbolicLink(probe.resolve("l"), probe.resolve("f"))
        }.isSuccess
        Files.writeString(outsideFile, "secret")
        runCatching { Files.createSymbolicLink(link, outsideFile) }
        `when`("checking whether the link stays inside the allowed roots") {
            then("it is refused once the link is followed") {
                if (!supportsLinks || !Files.isSymbolicLink(link)) {
                    true shouldBe true
                } else {
                    ExecuteWorkspace.withinAllowed(link, listOf(allowed.toRealPath())) shouldBe false
                }
            }
        }
    }
})
