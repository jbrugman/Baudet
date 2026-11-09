package dev.baudet.diff

/**
 * A line-level difference between two texts, and the only diff that can run
 * before a file is written -- git cannot help while the new content still lives
 * only in memory. A least-common-subsequence over lines marks each line context,
 * added or removed. Bounded on purpose: past [MAX_LINES] on either side the exact
 * LCS is quadratic, so it falls back to a coarse whole-block change, which is a
 * rough but always-terminating preview rather than a hang.
 */
object LineDiff {

    private const val MAX_LINES = 2000

    sealed class Op {
        data class Context(val text: String) : Op()
        data class Add(val text: String) : Op()
        data class Remove(val text: String) : Op()
    }

    /** The line-by-line difference between [oldText] and [newText]. */
    fun diff(oldText: String, newText: String): List<Op> {
        val a = lines(oldText)
        val b = lines(newText)
        if (a.size > MAX_LINES || b.size > MAX_LINES) return coarse(a, b)

        val n = a.size
        val m = b.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1
                else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val ops = ArrayList<Op>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { ops.add(Op.Context(a[i])); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { ops.add(Op.Remove(a[i])); i++ }
                else -> { ops.add(Op.Add(b[j])); j++ }
            }
        }
        while (i < n) { ops.add(Op.Remove(a[i])); i++ }
        while (j < m) { ops.add(Op.Add(b[j])); j++ }
        return ops
    }

    /** A whole-block remove-then-add, for inputs too large to align line by line. */
    private fun coarse(a: List<String>, b: List<String>): List<Op> =
        a.map { Op.Remove(it) } + b.map { Op.Add(it) }

    private fun lines(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.split('\n')
}
