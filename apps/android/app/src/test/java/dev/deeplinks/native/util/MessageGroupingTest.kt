package dev.deeplinks.native.util
import dev.deeplinks.native.MobileMessage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息分组（WI-005 / WI-006 抽出）单元测试。
 *
 * 验证：
 * - 连续 tool_call/tool_result 合并为 ToolGroup；
 * - <2 退化为 Single；
 * - groupKey 稳定 —— LazyColumn 用作 key 时不会因重组抖动。
 */
class MessageGroupingTest {

    private fun msg(id: String, role: String, seq: Long = 0L): MobileMessage =
        MobileMessage(id = id, role = role, text = "t-$id", time = 1_700_000_000_000L + seq)

    @Test
    fun `空输入返回空列表`() {
        assertEquals(emptyList<MessageGroup>(), groupMessages(emptyList()))
    }

    @Test
    fun `操作行只挂在每轮最后一条助手回复`() {
        val groups = groupMessages(
            listOf(
                msg("u-1", "user", 1),
                msg("a-1", "assistant", 2),
                msg("t-1", "tool_call", 3),
                msg("t-2", "tool_result", 4),
                msg("a-2", "assistant", 5),
                msg("u-2", "user", 6),
                msg("a-3", "assistant", 7),
                msg("t-3", "tool_call", 8),
            ),
        )
        assertEquals(setOf("a-2", "a-3"), turnEndAssistantIds(groups, running = false))
        // 最后一轮还在跑：只有已结束的上一轮挂操作行
        assertEquals(setOf("a-2"), turnEndAssistantIds(groups, running = true))
    }

    @Test
    fun `非工具消息保持 Single`() {
        val msgs = listOf(
            msg("u-1", "user", 1),
            msg("a-1", "assistant", 2),
            msg("r-1", "reasoning", 3),
        )
        val groups = groupMessages(msgs)
        assertEquals(3, groups.size)
        assertTrue(groups.all { it is MessageGroup.Single })
        assertEquals(listOf("u-1", "a-1", "r-1"), groups.map { it.groupKey })
    }

    @Test
    fun `连续 tool_call 聚合为 ToolGroup`() {
        val msgs = listOf(
            msg("u-1", "user", 1),
            msg("tc-1", "tool_call", 2),
            msg("tr-1", "tool_result", 3),
            msg("tc-2", "tool_call", 4),
            msg("tr-2", "tool_result", 5),
            msg("a-1", "assistant", 6),
        )
        val groups = groupMessages(msgs)
        assertEquals(3, groups.size)
        assertTrue(groups[0] is MessageGroup.Single)
        assertEquals("u-1", groups[0].groupKey)
        assertTrue(groups[1] is MessageGroup.ToolGroup)
        val toolGroup = groups[1] as MessageGroup.ToolGroup
        assertEquals(4, toolGroup.items.size)
        assertEquals(listOf("tc-1", "tr-1", "tc-2", "tr-2"), toolGroup.items.map { it.id })
        assertTrue(groups[2] is MessageGroup.Single)
    }

    @Test
    fun `单条 tool_call 退化为 Single`() {
        val msgs = listOf(
            msg("u-1", "user", 1),
            msg("tc-1", "tool_call", 2),
            msg("a-1", "assistant", 3),
        )
        val groups = groupMessages(msgs)
        assertEquals(3, groups.size)
        // tool_call 单独一条 → Single，不聚合
        assertTrue(groups[1] is MessageGroup.Single)
        assertEquals("tc-1", groups[1].groupKey)
    }

    @Test
    fun `工具段被非工具消息分隔时分别聚合`() {
        val msgs = listOf(
            msg("tc-1", "tool_call", 1),
            msg("tr-1", "tool_result", 2),
            msg("a-1", "assistant", 3),
            msg("tc-2", "tool_call", 4),
            msg("tr-2", "tool_result", 5),
            msg("tr-3", "tool_result", 6),
        )
        val groups = groupMessages(msgs)
        assertEquals(3, groups.size)
        assertTrue(groups[0] is MessageGroup.ToolGroup)
        assertEquals(2, (groups[0] as MessageGroup.ToolGroup).items.size)
        assertTrue(groups[1] is MessageGroup.Single)
        assertEquals("a-1", groups[1].groupKey)
        assertTrue(groups[2] is MessageGroup.ToolGroup)
        assertEquals(3, (groups[2] as MessageGroup.ToolGroup).items.size)
    }

    @Test
    fun `groupKey 只用首项 id，组变长时保持稳定`() {
        val msgs = listOf(
            msg("tc-1", "tool_call", 1),
            msg("tr-1", "tool_result", 2),
        )
        val group = groupMessages(msgs).first() as MessageGroup.ToolGroup
        assertEquals("tc-1-group", group.groupKey)
        val grown = groupMessages(msgs + msg("tc-2", "tool_call", 3)).first() as MessageGroup.ToolGroup
        assertEquals("tc-1-group", grown.groupKey)
    }

    @Test
    fun userTurnJumps_keepsUserPreviews() {
        val msgs = listOf(
            msg("u-1", "user", 1).copy(text = "第一轮\n细节"),
            msg("a-1", "assistant", 2),
            msg("u-2", "user", 3).copy(text = "第二轮"),
        )
        val jumps = userTurnJumps(msgs)
        assertEquals(listOf("u-1", "u-2"), jumps.map { it.messageId })
        assertEquals("第一轮", jumps.first().preview)
    }

    @Test
    fun userTurnJumps_skipsBlankUser() {
        assertEquals(emptyList<UserTurnJump>(), userTurnJumps(listOf(msg("u-1", "user", 1).copy(text = "  \n"))))
    }

    @Test
    fun `formatToolGroupSummary 测试单一与多工具摘要`() {
        val msgs1 = listOf(
            msg("tc-1", "tool_call", 1).copy(toolName = "Read"),
            msg("tr-1", "tool_result", 2),
            msg("tc-2", "tool_call", 3).copy(toolName = "Read"),
        )
        assertEquals("Read (2)", formatToolGroupSummary(msgs1))

        val msgs2 = listOf(
            msg("tc-1", "tool_call", 1).copy(toolName = "Read"),
            msg("tc-2", "tool_call", 2).copy(toolName = "bash"),
        )
        assertEquals("Read, bash (2)", formatToolGroupSummary(msgs2))

        val msgsEmpty = listOf(
            msg("tc-1", "tool_call", 1),
        )
        assertEquals("1 tool calls", formatToolGroupSummary(msgsEmpty))
    }

    /** 过程折叠行（方案 5.2）：已结束写「已完成工作 · 摘要」，执行中写「◌ 正在运行 命令」。 */
    @Test
    fun `toolGroupRowLabel 已结束与执行中两种口吻`() {
        val msgs = listOf(
            MobileMessage(id = "1", role = "tool_call", text = "", toolName = "Read"),
            MobileMessage(id = "2", role = "tool_call", text = "", toolName = "Read"),
        )
        assertEquals("已完成工作 · Read (2)", toolGroupRowLabel(msgs, running = false))
        assertEquals("◌ go test ./...", toolGroupRowLabel(msgs, running = true, runningCommand = "go test ./..."))
        // 拿不到命令（旧插件）时留空，由组头右侧的「执行中」标签说明，不编一个命令出来
        assertEquals("", toolGroupRowLabel(msgs, running = true, runningCommand = "  "))
    }

    /** 过程折叠行：能数出改动文件时写「编辑了 N 个文件」（稿 03），数不出才退回工具名。 */
    @Test
    fun `toolGroupRowLabel 优先写改动文件数`() {
        val edits = listOf(
            MobileMessage(id = "1", role = "tool_call", text = "", toolName = "edit",
                toolArgs = """{"file_path":"/a/HomeHub.kt","old_str":"x","new_str":"y"}"""),
            MobileMessage(id = "2", role = "tool_call", text = "", toolName = "edit",
                toolArgs = """{"file_path":"/a/HomeHub.kt","old_str":"x","new_str":"y"}"""),
            MobileMessage(id = "3", role = "tool_call", text = "", toolName = "write",
                toolArgs = """{"file_path":"/a/Theme.kt","content":"..."}"""),
            // 只读的工具不算改动
            MobileMessage(id = "4", role = "tool_call", text = "", toolName = "Read"),
        )
        assertEquals("已完成工作 · 已编辑 2 个文件", toolGroupRowLabel(edits, running = false))
        // 参数不全（缺 old_str）不算改动，退回工具名摘要
        val halfArgs = listOf(MobileMessage(id = "5", role = "tool_call", text = "", toolName = "edit", toolArgs = """{"file_path":"/a/x.kt"}"""))
        assertEquals("已完成工作 · edit", toolGroupRowLabel(halfArgs, running = false))
    }
}
