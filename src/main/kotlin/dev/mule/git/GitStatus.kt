package dev.mule.git

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
         * characters, are unquoted. Lines too short to hold a status and a path are dropped.
         */
        fun parsePorcelain(text: String): Map<String, FileChange> {
            val result = LinkedHashMap<String, FileChange>()
            for (line in text.lineSequence()) {
                if (line.length < 4) continue
                var path = line.substring(3)
                if (" -> " in path) path = path.substringAfter(" -> ")
                path = unquote(path)
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
