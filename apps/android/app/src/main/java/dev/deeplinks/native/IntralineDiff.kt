package dev.deeplinks.native

/**
 * 行内（词级）差异：把相邻的「删除块 + 新增块」逐行配对，标出行里真正变了的片段。
 *
 * - 分词：标识符 / 数字连成一词，空白连成一段，汉字等表意字与标点各自成词。
 * - 对齐：先剥掉首尾相同的词，中段做 LCS；未匹配的词即变化片段，只隔空白的相邻片段合并。
 * - 整行重写（变化超过 [INTRALINE_MAX_CHANGED_RATIO]）不标：满行高亮等于没标，只剩噪音。
 * - 成本有上限：单对超过 [INTRALINE_MAX_CELLS]、整份 diff 超过 [INTRALINE_BUDGET_CELLS] 就不再计算。
 */

internal const val INTRALINE_MAX_CHANGED_RATIO = 0.6
internal const val INTRALINE_MAX_CELLS = 40_000
internal const val INTRALINE_BUDGET_CELLS = 2_000_000

/** 单行分词，返回每个词在原串中的区间。 */
internal fun intralineTokens(s: String): List<IntRange> {
    val out = ArrayList<IntRange>()
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val start = i
        i += Character.charCount(cp)
        when {
            Character.isWhitespace(cp) ->
                while (i < s.length && Character.isWhitespace(s.codePointAt(i))) i += Character.charCount(s.codePointAt(i))
            isWordCodePoint(cp) ->
                while (i < s.length && isWordCodePoint(s.codePointAt(i))) i += Character.charCount(s.codePointAt(i))
        }
        out += start until i
    }
    return out
}

private fun isWordCodePoint(cp: Int): Boolean =
    (Character.isLetterOrDigit(cp) || cp == '_'.code) && !Character.isIdeographic(cp)

/** 一对旧行 / 新行的变化片段；不值得标时两侧都为空。[budget] 为剩余可用的 LCS 格数。 */
internal fun intralineEmphasis(
    old: String,
    new: String,
    budget: IntArray = intArrayOf(INTRALINE_BUDGET_CELLS),
): Pair<List<IntRange>, List<IntRange>> {
    val none = emptyList<IntRange>() to emptyList<IntRange>()
    if (old == new) return none
    val a = intralineTokens(old)
    val b = intralineTokens(new)
    fun tokA(i: Int) = old.substring(a[i].first, a[i].last + 1)
    fun tokB(j: Int) = new.substring(b[j].first, b[j].last + 1)

    var head = 0
    while (head < a.size && head < b.size && tokA(head) == tokB(head)) head++
    var tail = 0
    while (tail < a.size - head && tail < b.size - head && tokA(a.size - 1 - tail) == tokB(b.size - 1 - tail)) tail++
    val n = a.size - head - tail
    val m = b.size - head - tail
    val cells = n.toLong() * m
    if (cells > INTRALINE_MAX_CELLS || cells > budget[0]) return none
    budget[0] -= cells.toInt()

    val matchedA = BooleanArray(a.size) { it < head || it >= a.size - tail }
    val matchedB = BooleanArray(b.size) { it < head || it >= b.size - tail }
    if (n > 0 && m > 0) {
        // dp[i][j] = 中段 a[i..] 与 b[j..] 的 LCS 长度
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (tokA(head + i) == tokB(head + j)) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                tokA(head + i) == tokB(head + j) -> {
                    matchedA[head + i] = true
                    matchedB[head + j] = true
                    i++
                    j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
    }

    val oldRanges = changedRanges(old, a, matchedA)
    val newRanges = changedRanges(new, b, matchedB)
    if (tooMuchChanged(old, oldRanges) || tooMuchChanged(new, newRanges)) return none
    return oldRanges to newRanges
}

private fun changedRanges(s: String, tokens: List<IntRange>, matched: BooleanArray): List<IntRange> {
    val out = ArrayList<IntRange>()
    for ((k, r) in tokens.withIndex()) {
        if (matched[k]) continue
        val last = out.lastOrNull()
        val gapIsBlank = last != null && (last.last + 1 until r.first).all { s[it].isWhitespace() }
        if (last != null && gapIsBlank) out[out.lastIndex] = last.first..r.last else out += r
    }
    return out
}

private fun tooMuchChanged(s: String, ranges: List<IntRange>): Boolean {
    val visible = s.count { !it.isWhitespace() }
    if (visible == 0) return false
    val changed = ranges.sumOf { r -> r.count { !s[it].isWhitespace() } }
    return changed > visible * INTRALINE_MAX_CHANGED_RATIO
}

internal const val INTRALINE_PAIR_WINDOW = 8

/**
 * 给相邻「删除块 → 新增块」配对并填入 [DiffRow.emphasis]。保持顺序：每条删除行从上次配上的
 * 新增行之后、[INTRALINE_PAIR_WINDOW] 行内找第一条「值得标」的；找不到就不配（插在中间的注释不再错位）。
 */
internal fun withIntralineEmphasis(rows: List<DiffRow>): List<DiffRow> {
    val out = rows.toMutableList()
    val budget = intArrayOf(INTRALINE_BUDGET_CELLS)
    var i = 0
    while (i < out.size) {
        if (out[i].kind != DiffRow.Kind.DELETE) {
            i++
            continue
        }
        val delStart = i
        while (i < out.size && out[i].kind == DiffRow.Kind.DELETE) i++
        val addStart = i
        while (i < out.size && out[i].kind == DiffRow.Kind.ADD) i++
        val addEnd = i
        var nextAdd = addStart
        for (d in delStart until addStart) {
            if (nextAdd >= addEnd) break
            val del = out[d]
            for (a in nextAdd until minOf(addEnd, nextAdd + INTRALINE_PAIR_WINDOW)) {
                val add = out[a]
                val (oldR, newR) = intralineEmphasis(del.text, add.text, budget)
                if (oldR.isEmpty() && newR.isEmpty()) continue
                out[d] = del.copy(emphasis = oldR)
                out[a] = add.copy(emphasis = newR)
                nextAdd = a + 1
                break
            }
        }
    }
    return out
}
