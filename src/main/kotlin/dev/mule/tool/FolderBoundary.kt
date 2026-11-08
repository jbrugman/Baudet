package dev.mule.tool

import java.nio.file.Path

/**
 * The one place that decides whether a path a tool is about to touch really stays
 * inside a project folder. The lexical startsWith check a caller does first only
 * folds the literal path; it does not resolve symlinks, so a link that lives inside
 * the folder but points outside would pass it and then be read or written through
 * the link. This resolves the real target, following every symlink, and returns it
 * only when it stays under the real root, otherwise null. It requires the path to
 * exist, which both file tools already guarantee before they call it.
 */
object FolderBoundary {

    /** @return the real, symlink-resolved path when it stays inside [root], else null. */
    fun realWithin(root: Path, resolved: Path): Path? {
        val realRoot = runCatching { root.toRealPath() }.getOrNull() ?: return null
        val real = runCatching { resolved.toRealPath() }.getOrNull() ?: return null
        return if (real.startsWith(realRoot)) real else null
    }
}
