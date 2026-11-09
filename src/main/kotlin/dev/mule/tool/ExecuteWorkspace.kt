package dev.mule.tool

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Decides the folders a run is allowed to use, checked before the run script is written.
 * A command runs with the project folder as its working directory and stages its script in
 * the system temp folder; this resolves both through every symlink and refuses when either
 * is not what it claims to be. It is deliberately narrow. It cannot contain the command,
 * only confirm the two folders we ourselves touch are the real, writable ones we expect.
 */
object ExecuteWorkspace {

    /** The real project directory, or null when [root] is not an existing directory. */
    fun projectDir(root: Path): Path? {
        val real = runCatching { root.toRealPath() }.getOrNull() ?: return null
        return if (Files.isDirectory(real)) real else null
    }

    /** The real, writable system temporary directory, or null when none is usable. */
    fun tempDir(): Path? {
        val real = runCatching { Paths.get(System.getProperty("java.io.tmpdir")).toRealPath() }
            .getOrNull() ?: return null
        return if (Files.isDirectory(real) && Files.isWritable(real)) real else null
    }

    /**
     * True when [target] resolves, through every symlink, inside one of [allowedRoots].
     * Confirms the written script landed where we meant, not through a redirected link.
     */
    fun withinAllowed(target: Path, allowedRoots: List<Path>): Boolean {
        val real = runCatching { target.toRealPath() }.getOrNull() ?: return false
        return allowedRoots.any { real.startsWith(it) }
    }
}
