package dev.baudet.fs

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads one directory level of a project folder. It is deliberately side effect free
 * apart from the listing itself, so it can be tested against a temporary tree without
 * the UI. Ordering is deterministic -- directories first, then files, each group
 * alphabetical and case-insensitive -- which keeps the tree stable across refreshes.
 */
object FileLister {

    /** The children of [dir]; empty when it is missing, not readable, or not a directory. */
    fun list(dir: Path, showHidden: Boolean = false): List<FileEntry> {
        if (!Files.isDirectory(dir) || !Files.isReadable(dir)) return emptyList()
        val entries = ArrayList<FileEntry>()
        try {
            Files.newDirectoryStream(dir).use { stream ->
                for (child in stream) {
                    val name = child.fileName.toString()
                    if (!showHidden && name.startsWith(".")) continue
                    entries.add(FileEntry(name, child, Files.isDirectory(child)))
                }
            }
        } catch (_: IOException) {
            // A folder that vanishes mid-scan, or one entry that cannot be read, must not
            // abort the whole listing; return what was collected before the failure.
        }
        return entries.sortedWith(
            compareByDescending<FileEntry> { it.isDirectory }
                .thenComparator { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name) }
        )
    }
}
