package dev.baudet.tool

import java.nio.file.Files
import java.nio.file.Path

/**
 * The one place that decides whether a path a tool is about to touch really stays
 * inside a project folder. The lexical startsWith checks a caller does first only
 * fold the literal path; they do not resolve symlinks, so a link that lives inside
 * the folder but points outside would pass them and then be read or written through
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

    /** Either a regular file that stays inside the folder, or a refusal reason for the model. */
    sealed interface Resolved {
        data class Inside(val path: Path) : Resolved
        data class Refused(val reason: String) : Resolved
    }

    /**
     * Resolves [rel] inside [root] and confirms it is a regular file that stays inside the
     * folder once links are followed. Shared by the read and edit tools so a wrong path is
     * rejected the same way in both, with the same model-facing wording.
     */
    fun resolveFileWithin(root: Path, rel: String): Resolved {
        val resolved = root.resolve(rel).normalize()
        if (!resolved.startsWith(root)) return Resolved.Refused("$rel resolves outside the project folder")
        if (!Files.isRegularFile(resolved)) return Resolved.Refused("$rel does not exist inside the folder.${FolderListing.hint(root)}")
        if (realWithin(root, resolved) == null) return Resolved.Refused("$rel resolves outside the folder once links are followed")
        return Resolved.Inside(resolved)
    }
}
