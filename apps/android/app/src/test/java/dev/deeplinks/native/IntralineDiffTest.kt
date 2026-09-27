package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntralineDiffTest {
    private fun String.pieces(ranges: List<IntRange>) = ranges.map { substring(it.first, it.last + 1) }

    @Test
    fun `tokens split words whitespace punctuation and ideographs`() {
        val s = "val x_1 = 改动(a)"
        assertEquals(
            listOf("val", " ", "x_1", " ", "=", " ", "改", "动", "(", "a", ")"),
            s.pieces(intralineTokens(s)),
        )
    }

    @Test
    fun `renamed argument is the only emphasis`() {
        val old = "    val progress = Animatable(0f)"
        val new = "    val progress = Animatable(initialProgress)"
        val (o, n) = intralineEmphasis(old, new)
        assertEquals(listOf("0f"), old.pieces(o))
        assertEquals(listOf("initialProgress"), new.pieces(n))
    }

    @Test
    fun `adjacent changed words merge across blanks`() {
        val old = "if (visible) show(panel, fast)"
        val new = "if (visible) show(panel, very slow)"
        val (o, n) = intralineEmphasis(old, new)
        assertEquals(listOf("fast"), old.pieces(o))
        assertEquals(listOf("very slow"), new.pieces(n))
    }

    @Test
    fun `pure insertion marks only the new side`() {
        val old = "val visible: Boolean get() = progress.value > 0f"
        val new = "val visible: Boolean get() = progress.value > 0f || pending"
        val (o, n) = intralineEmphasis(old, new)
        assertTrue(o.isEmpty())
        assertEquals(listOf(" || pending"), new.pieces(n))
    }

    @Test
    fun `whole line rewrite is not emphasised`() {
        val (o, n) = intralineEmphasis("return cachedValue", "throw IllegalStateException()")
        assertTrue(o.isEmpty() && n.isEmpty())
    }

    @Test
    fun `chinese edits highlight single characters`() {
        val old = "草稿不跨进程持久化"
        val new = "草稿按主机持久化"
        val (o, n) = intralineEmphasis(old, new)
        assertEquals(listOf("不跨进程"), old.pieces(o))
        assertEquals(listOf("按主机"), new.pieces(n))
    }

    @Test
    fun `oversized pair and exhausted budget are skipped`() {
        val old = (0 until 400).joinToString(" ") { "a$it" }
        val new = (0 until 400).joinToString(" ") { if (it % 50 == 0) "b$it" else "a$it" }
        assertTrue(intralineEmphasis(old, new).first.isEmpty())
        val (o, _) = intralineEmphasis("call(a, b)", "call(a, c)", budget = intArrayOf(0))
        assertTrue(o.isEmpty())
    }

    @Test
    fun `rows pair deletes with following adds in order`() {
        val rows = diffRows(
            listOf(
                DiffHunk(
                    oldStart = 1, oldLines = 3, newStart = 1, newLines = 2,
                    lines = listOf("-foo(1)", "-bar(2)", "-extra line", "+foo(10)", "+bar(20)", " tail"),
                ),
            ),
        )
        val del = rows.filter { it.kind == DiffRow.Kind.DELETE }
        val add = rows.filter { it.kind == DiffRow.Kind.ADD }
        assertEquals(listOf("1"), del[0].text.pieces(del[0].emphasis))
        assertEquals(listOf("20"), add[1].text.pieces(add[1].emphasis))
        assertTrue(del[2].emphasis.isEmpty())
        assertTrue(rows.last().emphasis.isEmpty())
    }

    @Test
    fun `inserted comment line does not steal the pairing`() {
        val rows = diffRows(
            listOf(
                DiffHunk(
                    oldStart = 1, oldLines = 1, newStart = 1, newLines = 2,
                    lines = listOf("-    val progress = Animatable(0f)", "+    /** doc */", "+    val progress = Animatable(initialProgress)"),
                ),
            ),
        )
        val del = rows.single { it.kind == DiffRow.Kind.DELETE }
        val adds = rows.filter { it.kind == DiffRow.Kind.ADD }
        assertEquals(listOf("0f"), del.text.pieces(del.emphasis))
        assertTrue(adds[0].emphasis.isEmpty())
        assertEquals(listOf("initialProgress"), adds[1].text.pieces(adds[1].emphasis))
    }
}
