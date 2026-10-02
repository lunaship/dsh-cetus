package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v4 4.7 轨迹页的筛选与标题。 */
class TrajectoryFilterTest {
    private val call = MobileMessage(id = "t1", role = "tool_call", text = "", toolName = "bash", toolArgs = """{"command":"go test ./..."}""")
    private val failed = MobileMessage(id = "r1", role = "tool_result", text = "boom", outcome = "error")
    private val ok = MobileMessage(id = "r2", role = "tool_result", text = "ok", outcome = "ok")
    private val thinking = MobileMessage(id = "th", role = "reasoning", text = "想一想")

    @Test
    fun filtersByKindAndError() {
        val errRow = TraceRow("a", call, failed)
        val okRow = TraceRow("b", call, ok)
        val thinkRow = TraceRow("c", thinking, null)
        assertTrue(traceRowMatches(errRow, TraceFilter.Error, ""))
        assertFalse(traceRowMatches(okRow, TraceFilter.Error, ""))
        assertTrue(traceRowMatches(okRow, TraceFilter.Tool, ""))
        assertFalse(traceRowMatches(thinkRow, TraceFilter.Tool, ""))
        assertTrue(traceRowMatches(thinkRow, TraceFilter.Thinking, ""))
    }

    @Test
    fun searchLooksAtArgsAndResult() {
        val row = TraceRow("a", call, failed)
        assertTrue(traceRowMatches(row, TraceFilter.All, "go test"))
        assertTrue(traceRowMatches(row, TraceFilter.All, "BOOM"))
        assertFalse(traceRowMatches(row, TraceFilter.All, "nothing"))
    }

    @Test
    fun toolTitlePrefersCommandThenPath() {
        assertEquals("go test ./...", traceToolTitle(call))
        assertEquals("a.kt", traceToolTitle(call.copy(toolArgs = """{"file_path":"a.kt"}""")))
        assertEquals("bash", traceToolTitle(call.copy(toolArgs = null)))
    }

    @Test
    fun summaryCountsRoundsAndCalls() {
        val msgs = listOf(MobileMessage(id = "u", role = "user", text = "hi"), call, ok)
        val line = traceSummaryLine(msgs)
        assertTrue(line, line.contains("1"))
        assertEquals(2, line.split(" · ").size)
    }
}
