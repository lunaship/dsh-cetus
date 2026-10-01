package dev.deeplinks.native.util

import dev.deeplinks.native.MobileMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工具活动分类计数（2026-10-02 Lody 简化 4.3）单测：
 * 大小写不敏感、未知归 Other、阅读按 path 去重、编辑按调用次数、
 * 超 3 类合并「+N」、全 Other 回退、reasoning 时长并入。
 */
class ToolActivityTest {

    private fun call(id: String, name: String?, args: String? = null): MobileMessage =
        MobileMessage(id = id, role = "tool_call", text = "", toolName = name, toolArgs = args)

    private fun reasoning(id: String, durationMs: Long?): MobileMessage =
        MobileMessage(id = id, role = "reasoning", text = "", durationMs = durationMs)

    @Test
    fun `工具名大小写不敏感`() {
        assertEquals(ToolKind.Command, classifyTool("Bash"))
        assertEquals(ToolKind.Command, classifyTool("SHELL"))
        assertEquals(ToolKind.Read, classifyTool("read_file"))
        assertEquals(ToolKind.Other, classifyTool(null))
        assertEquals(ToolKind.Other, classifyTool("  "))
    }

    @Test
    fun `shell 与 exec 系都归命令`() {
        assertEquals(ToolKind.Command, classifyTool("shell"))
        assertEquals(ToolKind.Command, classifyTool("exec"))
        assertEquals(ToolKind.Command, classifyTool("exec_command"))
        assertEquals(ToolKind.Command, classifyTool("run_code"))
        assertEquals(ToolKind.Command, classifyTool("terminal"))
    }

    @Test
    fun `未知工具归 Other`() {
        assertEquals(ToolKind.Other, classifyTool("mcp__custom__zap"))
        val counts = activityCounts(listOf(call("1", "mcp__custom__zap"), call("2", "weather")))
        assertEquals(2, counts.other)
    }

    @Test
    fun `阅读按 path 去重`() {
        val counts = activityCounts(
            listOf(
                call("1", "read", """{"file_path":"/a/A.kt"}"""),
                call("2", "read_file", """{"file_path":"/a/A.kt"}"""),
                call("3", "read", """{"file_path":"/a/B.kt"}"""),
                call("4", "ls"), // 参数解析不出 path：按调用次数计
            ),
        )
        assertEquals(3, counts.read)
    }

    @Test
    fun `编辑按调用次数计`() {
        val counts = activityCounts(
            listOf(
                call("1", "edit", """{"file_path":"/a/A.kt","old_str":"x"}"""),
                call("2", "edit", """{"file_path":"/a/A.kt","old_str":"y"}"""),
                call("3", "write", """{"file_path":"/a/B.kt","content":"z"}"""),
            ),
        )
        // 同一个文件改两次是两次编辑；文件数只认改动卡（L7）
        assertEquals(3, counts.edit)
    }

    @Test
    fun `超过 3 类合并 +N 并入最后一段`() {
        val counts = ActivityCounts(
            command = 12,
            read = 38,
            edit = 4,
            search = 11,
            fetch = 2,
            thinkingMs = 8_000,
        )
        val line = activityLine(counts)
        // 思考 + 3 类展示，剩下 编辑/搜索/获取 三类并入「+3」
        assertEquals(3, line.size)
        assertEquals("思考 8 秒", line[0])
        assertEquals("调用了 12 个命令", line[1])
        assertEquals("阅读了 38 个文件 +3", line[2])
    }

    @Test
    fun `全部为 Other 时回退工具总数`() {
        val counts = activityCounts(listOf(call("1", "mcp__a"), call("2", "mcp__b"), call("3", "mcp__c")))
        assertEquals(listOf("调用了 3 个工具"), activityLine(counts))
    }

    @Test
    fun `reasoning 时长并入`() {
        val counts = activityCounts(
            listOf(
                reasoning("1", 4_000),
                call("2", "grep", """{"pattern":"x"}"""),
                reasoning("3", 8_500),
            ),
        )
        assertEquals(12_500L, counts.thinkingMs)
        assertEquals(1, counts.search)
        assertEquals(listOf("思考 12 秒", "进行了 1 次搜索"), activityLine(counts))
    }

    @Test
    fun `空活动返回空片段`() {
        assertEquals(emptyList<String>(), activityLine(ActivityCounts()))
        assertEquals(emptyList<String>(), activityLine(activityCounts(emptyList())))
    }
}
