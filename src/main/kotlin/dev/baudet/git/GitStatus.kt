package dev.baudet.git

/**
 * The set of working-tree changes git reports for one project folder, keyed by the path
 * relative to that folder. It is a snapshot the file tree reads once per refresh to decide
 * a row color and tooltip, and to know which deleted files to show as ghosts under a folder.
 *
 * Paths are taken as git prints them, relative and slash-separated, so a lookup is an exact
 * map hit. Repository detection lives in [GitInspector]; a folder that is not a repository is
 * represented by [NOT_A_REPO], which reports no change for any path.
 */
class GitStatus(val isRepository: Boolean, private val byPath: Map<String, FileChange>) {

    /** The coarse change for [relativePath], or null when git sees no change there. */
    fun changeOf(relativePath: String): FileChange? = byPath[relativePath]

    /**
     * Every change git reported, keyed by path relative to the project folder, in the order
     * porcelain listed them. This is what the change list below the tree reads; [changeOf]
     * stays the lookup for a single row, since the tree asks per visible file.
     */
    val entries: Map<String, FileChange> get() = byPath

    /** The names of files git reports deleted directly inside [dirRelative] (empty for the root). */
    fun deletedUnder(dirRelative: String): List<String> =
        byPath.entries
            .filter { it.value == FileChange.Deleted && it.key.substringBeforeLast('/', "") == dirRelative }
            .map { it.key.substringAfterLast('/') }
            .sorted()


    companion object {
        /** The status to use when the folder is not a repository: nothing ever shows as changed. */
        val NOT_A_REPO = GitStatus(false, emptyMap())

        /**
         * Parses the output of [git status --porcelain=v1](https://git-scm.com/docs/git-status) into
         * the coarse buckets. Each line is two status letters, a space, then the path; a rename reads
         * as OLD -> NEW and is stored under NEW. Quoted paths, which git uses for names with odd
         * characters, are unquoted. Lines too short to hold a status and a path are dropped. When
         * [prefix] is non-empty it is stripped from the front of each path, so a project folder that
         * is a repository subdirectory still keys by paths relative to that folder.
         */
        fun parsePorcelain(text: String, prefix: String = ""): Map<String, FileChange> {
            val result = LinkedHashMap<String, FileChange>()
            for (line in text.lineSequence()) {
                if (line.length < 4) continue
                var path = line.substring(3)
                if (" -> " in path) path = path.substringAfter(" -> ")
                path = unquote(path)
                if (prefix.isNotEmpty() && path.startsWith(prefix)) path = path.removePrefix(prefix)
                if (path.isEmpty()) continue
                result[path] = category(line[0], line[1])
            }
            return result
        }

        /** Folds the two porcelain letters into one bucket; precedence runs new -> gone -> changed. */
        private fun category(x: Char, y: Char): FileChange = when {
            x == '?' || y == '?' -> FileChange.Untracked
            x == 'R' || y == 'R' -> FileChange.Renamed
            x == 'D' || y == 'D' -> FileChange.Deleted
            x == 'A' || y == 'A' || x == 'C' || y == 'C' -> FileChange.Added
            x == 'M' || y == 'M' -> FileChange.Modified
            else -> FileChange.Other
        }

        /** Reverses the C-style quoting git applies to a path wrapped in double quotes. */
        private fun unquote(p: String): String {
            if (p.length < 2 || p.first() != '"' || p.last() != '"') return p
            val inner = p.substring(1, p.length - 1)
            val out = StringBuilder(inner.length)
            var i = 0
            while (i < inner.length) {
                val c = inner[i]
                if (c == '\\' && i + 1 < inner.length) {
                    when (val n = inner[i + 1]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        else -> out.append('\\').append(n)
                    }
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }
    }
}

/**
 * One row of the change list shown under the file tree: the path as git prints it (relative
 * to the project folder, slash-separated) and the coarse kind of change.
 */
data class ChangeRow(val relPath: String, val change: FileChange) {

    /** The file name alone, without the folders in front of it. */
    val name: String get() = relPath.substringAfterLast('/')

    /** The folder the file sits in, as git writes it; empty for a file at the project root. */
    val parent: String get() = relPath.substringBeforeLast('/', "")
}

/**
 * Which rows the change list shows, in what order, and with which one-letter mark. Kept free
 * of Swing so the ordering and the letter mapping are testable on their own, the way
 * [dev.baudet.ui.WindowLayout] keeps the split-pane rule out of the panels.
 *
 * The rows are exactly what git reports, which is what makes the list say "what has this
 * project changed" instead of "what is on disk": a deleted file stays a row although nothing
 * is there anymore, and an unchanged file never appears although the tree above shows it.
 */
object ChangesList {

    /**
     * Every reported change, ordered so the files of one folder stay together: by folder, then
     * by name, then by the whole path as a tie-break. Porcelain's own order is not stable enough
     * to scan, since a staged file or a rename can land anywhere in it.
     */
    fun rows(status: GitStatus): List<ChangeRow> =
        status.entries.map { ChangeRow(it.key, it.value) }
            .sortedWith(compareBy({ it.parent }, { it.name }, { it.relPath }))

    /**
     * The letter in front of a row. Deliberately the porcelain letter where one exists, so the
     * list reads like `git status` in a terminal; `?` for untracked and `~` for [FileChange.Other],
     * a tracked change none of the letters names.
     */
    fun letter(change: FileChange): Char = when (change) {
        FileChange.Untracked -> '?'
        FileChange.Added -> 'A'
        FileChange.Modified -> 'M'
        FileChange.Deleted -> 'D'
        FileChange.Renamed -> 'R'
        FileChange.Other -> '~'
    }
}
