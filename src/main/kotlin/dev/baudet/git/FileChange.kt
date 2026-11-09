package dev.baudet.git

/**
 * What git considers to have happened to one path in the project folder, reduced to the
 * few kinds the file tree needs to color and tag a row. It is deliberately coarse: the
 * tree only distinguishes new from changed from gone, so the exact staged-versus-unstaged
 * letters are folded into these buckets by [GitStatus.parsePorcelain] rather than kept.
 */
enum class FileChange {
    /** Present in the working tree but unknown to git. */
    Untracked,

    /** Tracked as an addition (a staged new file, or the new side of a rename or copy). */
    Added,

    /** Tracked, not new, with content or mode changes. */
    Modified,

    /** Tracked but deleted in the working tree or the index; the file may no longer exist. */
    Deleted,

    /** Tracked under a different name; mapped to the working-tree path. */
    Renamed,

    /** Any other tracked change the coarse buckets do not name. */
    Other
}
