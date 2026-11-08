package dev.mule.git

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Shells out to the git command line to answer two questions about a project folder: what
 * changed (for the tree colors) and what the patch for one file is (for the diff viewer).
 *
 * git is resolved from PATH, which for a bundled macOS app resolves to the system git under
 * /usr/bin. Every call is bounded: a repository that hangs or a git that is missing yields
 * null, and the caller degrades to no colors and an empty diff rather than blocking the UI.
 * core.quotePath is forced off so paths stay readable. git status runs inside the folder and
 * is scoped to it with a dot pathspec, so its paths come back relative to that folder and line
 * up with the tree, which shows names relative to the project folder.
 */
class GitInspector {

    /** Whether [root] is inside a work tree, and if so the changes inside it. */
    fun status(root: Path): GitStatus {
        val insideWorkTree = exec(root, "rev-parse", "--is-inside-work-tree")?.trim() == "true"
        if (!insideWorkTree) return GitStatus.NOT_A_REPO
        val out = exec(root, "-c", "core.quotePath=false", "status", "--porcelain=v1", "-uall", "--", ".")
            ?: return GitStatus(true, emptyMap())
        return GitStatus(true, GitStatus.parsePorcelain(out))
    }

    /**
     * The unified diff for one file, preferring unstaged changes and falling back to the index.
     * Empty for a new untracked file or a file with no committed version to compare against.
     */
    fun diffPatch(root: Path, relativePath: String): String {
        val unstaged = exec(root, "-c", "core.quotePath=false", "diff", "--", relativePath)?.trim().orEmpty()
        if (unstaged.isNotEmpty()) return unstaged
        return exec(root, "-c", "core.quotePath=false", "diff", "--cached", "--", relativePath)?.trim().orEmpty()
    }

    /**
     * Runs git in [root] and returns stdout, or null on any failure. Errors are folded into
     * stdout, which is discarded unless the process exits zero. Output is drained on a daemon
     * thread so a large diff cannot deadlock the pipe; a call that outlives the timeout is killed.
     */
    private fun exec(root: Path, vararg args: String): String? = try {
        val command = ArrayList<String>()
        command.add(GIT)
        command.add("-C")
        command.add(root.toString())
        command.addAll(args)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.close()
        val out = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().use { r ->
                var line = r.readLine()
                while (line != null) {
                    out.append(line).append('\n')
                    line = r.readLine()
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.interrupt()
            null
        } else {
            reader.join(1_000)
            if (process.exitValue() == 0) out.toString() else null
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val GIT = "git"
        const val TIMEOUT_SECONDS = 5L
    }
}
