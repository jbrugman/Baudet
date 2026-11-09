package dev.baudet.fs

import java.nio.file.Path

/** One entry read from a project folder: a file or a directory, with the path it came from. */
data class FileEntry(
    val name: String,
    val path: Path,
    val isDirectory: Boolean
)
