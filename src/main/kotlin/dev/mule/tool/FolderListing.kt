package dev.mule.tool

import java.nio.file.Files
import java.nio.file.Path

/**
 * A bounded, hidden-free listing of a project folder as a model-facing hint, so a wrong
 * path becomes a correctable mistake rather than a dead end. Scoped to the root, capped
 * in both depth and count, and never descending into dot-directories, so it cannot leak
 * the wider disk and stays small enough to feed back cheaply. Shared by the file tools so
 * both name the folder the same way when a path does not resolve.
 */
object FolderListing {

    private const val MAX_DEPTH = 3
    private const val MAX_ENTRIES = 50

    /** Returns an empty string when nothing is listed, else a leading space and the sorted names. */
    fun hint(root: Path): String {
        val names = mutableListOf<String>()
        walk(root, root, 1, names)
        return if (names.isEmpty()) "" else " Files here: " + names.sorted().joinToString(", ")
    }

    private fun walk(root: Path, dir: Path, depth: Int, out: MutableList<String>) {
        if (depth > MAX_DEPTH || out.size >= MAX_ENTRIES) return
        val children = runCatching { Files.newDirectoryStream(dir).use { it.toList() } }.getOrNull() ?: return
        children.sorted().forEach { child ->
            val name = child.fileName.toString()
            if (name.startsWith(".")) return@forEach
            when {
                Files.isDirectory(child) -> walk(root, child, depth + 1, out)
                Files.isRegularFile(child) && out.size < MAX_ENTRIES -> out.add(root.relativize(child).toString())
            }
        }
    }
}
