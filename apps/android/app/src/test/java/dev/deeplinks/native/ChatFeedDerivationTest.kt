package dev.deeplinks.native

import dev.deeplinks.native.util.MessageGroup
import dev.deeplinks.native.util.foldToolCalls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 消息流数据推导测试：这些规则原先内联在 WorkspaceScreen 的 LazyColumn 里，无法单测。
 */
class ChatFeedDerivationTest {
    /** 源码扫描测试的路径解析：从 user.dir 向上找 src/main/java，不写死开发机绝对路径。 */
    private fun mainRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        return File(requireNotNull(dir) { "找不到 src/main/java" }, "src/main/java")
    }

    private fun source(name: String): File = mainRoot().resolve("dev/deeplinks/native/$name")


    private fun msg(
        id: String,
        role: String,
        text: String = "text",
        running: Boolean? = null,
        toolName: String? = null,
        durationMs: Long? = null,
        time: Long = 0L,
    ) = MobileMessage(
        id = id,
        role = role,
        text = text,
        running = running,
        toolName = toolName,
        durationMs = durationMs,
        time = time,
    )

    private fun toolGroupMsg(id: String, toolName: String?, text: String = "tool", time: Long = 0L) =
        msg(id, "tool_call", text = text, toolName = toolName, time = time)

    @Test
    fun blankCompletedReasoningRowsAreDropped() {
        val feed = deriveChatFeed(
            olderMessages = emptyList(),
            messages = listOf(
                msg("r1", "reasoning", text = "", running = false),
                msg("a1", "assistant", text = "hi"),
            ),
        )
        assertEquals(1, feed.visibleGroups.size)
    }

    @Test
    fun runningReasoningRowsAreKept() {
        val feed = deriveChatFeed(emptyList(), listOf(msg("r1", "reasoning", text = "", running = true)))
        assertEquals(1, feed.visibleGroups.size)
    }

    @Test
    fun lastCompletedAssistantIgnoresStreamingOne() {
        val feed = deriveChatFeed(
            emptyList(),
            listOf(
                msg("a1", "assistant", "done"),
                msg("a2", "assistant", "streaming", running = true),
            ),
        )
        assertEquals("a1", feed.lastCompletedAssistantId)
    }

    @Test
    fun lastCompletedAssistantIsNullWithoutAssistant() {
        assertNull(deriveChatFeed(emptyList(), listOf(msg("u1", "user"))).lastCompletedAssistantId)
    }

    @Test
    fun olderHistoryPagesMergeWithLiveMessages() {
        val feed = deriveChatFeed(
            olderMessages = listOf(msg("a1", "assistant", "old")),
            messages = listOf(msg("a2", "assistant", "new")),
        )
        assertEquals(2, feed.visibleGroups.size)
    }

    @Test
    fun sweepingIdIsNullWhenNotRunning() {
        assertNull(resolveSweepingId(listOf(msg("t1", "tool_call", running = true)), running = false))
    }

    @Test
    fun sweepingIdPicksLastRunningToolOrReasoningRow() {
        val messages = listOf(
            msg("t1", "tool_call", running = true),
            msg("a1", "assistant", "settled"),
            msg("r1", "reasoning", text = "…", running = true),
        )
        assertEquals("r1", resolveSweepingId(messages, running = true))
    }

    @Test
    fun completedRowsNeverSweep() {
        val messages = listOf(
            msg("t1", "tool_call", running = false),
            msg("a1", "assistant", "done", running = false),
        )
        assertNull(resolveSweepingId(messages, running = true))
    }

    @Test
    fun blankToolQueryKeepsEveryGroup() {
        val feed = deriveChatFeed(emptyList(), listOf(msg("u1", "user")))
        assertEquals(1, feed.visibleGroups.size)
        assertTrue(feed.visibleGroups.isNotEmpty())
    }

    @Test
    fun contextInjectionRowsAreHiddenButGoalRoundsStay() {
        val feed = deriveChatFeed(
            emptyList(),
            listOf(
                msg("c1", "context_injection", text = "Current runtime context: ..."),
                msg("g1", "user", text = "<goal_round>\nObjective: \"ship it\"\n</goal_round>"),
                msg("a1", "assistant", text = "done"),
            ),
        )
        assertEquals(2, feed.visibleGroups.size)
    }

    @Test
    fun goalRoundRoleMessageIsKeptEvenThoughInjectionText() {
        val feed = deriveChatFeed(
            emptyList(),
            listOf(
                msg("g1", "context_injection", text = "<goal_round>Round: 1/3</goal_round>"),
            ),
        )
        assertEquals(1, feed.visibleGroups.size)
    }

    @Test
    fun duplicateMessageIdsCollapseToUniqueKeys() {
        val feed = deriveChatFeed(
            emptyList(),
            listOf(
                msg("dup", "assistant", text = "first"),
                msg("dup", "assistant", text = "second"),
                msg("b", "assistant", text = "other"),
            ),
        )
        val keys = feed.visibleGroups.map { it.groupKey }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(2, keys.size)
    }

    @Test
    fun dedupeByIdKeepsBlankIdsSeparate() {
        val list = listOf(
            msg("", "assistant", text = "a"),
            msg("", "assistant", text = "b"),
            msg("x", "assistant", text = "c"),
            msg("x", "assistant", text = "d"),
        )
        assertEquals(3, dedupeById(list).size)
    }

    // ---- shouldShowTurnStatus ----

    @Test
    fun `shouldShowTurnStatus returns false when not running`() {
        assertFalse(shouldShowTurnStatus(emptyList(), running = false))
    }

    @Test
    fun `shouldShowTurnStatus returns false when last item is running reasoning`() {
        val items = listOf(msg("r1", "reasoning", text = "...", running = true))
        assertFalse(shouldShowTurnStatus(items, running = true))
    }

    @Test
    fun `shouldShowTurnStatus returns true when running but no live reasoning`() {
        val items = listOf(msg("a1", "assistant", text = "ok", running = false))
        assertTrue(shouldShowTurnStatus(items, running = true))
    }

    // ---- isTurnEnd ----

    @Test
    fun `isTurnEnd true when next is user message`() {
        val groups = listOf(
            MessageGroup.Single(msg("a1", "assistant", text = "a")),
            MessageGroup.Single(msg("u1", "user", text = "b")),
        )
        assertTrue(isTurnEnd(groups, 0, running = true))
    }

    @Test
    fun `isTurnEnd true when last and not running`() {
        val groups = listOf(
            MessageGroup.Single(msg("a1", "assistant", text = "a")),
        )
        assertTrue(isTurnEnd(groups, 0, running = false))
    }

    @Test
    fun `isTurnEnd false when not assistant message`() {
        val groups = listOf(
            MessageGroup.Single(msg("u1", "user", text = "a")),
        )
        assertFalse(isTurnEnd(groups, 0, running = false))
    }

    @Test
    fun `isTurnEnd false when running and not last`() {
        val groups = listOf(
            MessageGroup.Single(msg("a1", "assistant", text = "a")),
            MessageGroup.Single(msg("r1", "reasoning", text = "...", running = true)),
        )
        assertFalse(isTurnEnd(groups, 0, running = true))
    }

    // ---- foldToolCalls ----

    @Test
    fun `foldToolCalls merges consecutive tools in chat mode`() {
        val items = listOf(
            MessageGroup.ToolGroup(listOf(toolGroupMsg("t1", "read_file", time = 1000))),
            MessageGroup.ToolGroup(listOf(toolGroupMsg("t2", "write_file", time = 2000))),
        )
        val folded = foldToolCalls(items, viewMode = "chat")
        assertEquals(1, folded.size)
        val summary = folded[0] as MessageGroup.ToolSummary
        assertEquals(2, summary.count)
        assertEquals(listOf("read_file", "write_file"), summary.toolNames.sorted())
    }

    @Test
    fun `foldToolCalls does not merge across approval cards`() {
        val items = listOf(
            MessageGroup.ToolGroup(listOf(toolGroupMsg("t1", "read_file", time = 1000))),
            MessageGroup.Single(msg("ap1", "approval", text = "approve?")),
            MessageGroup.ToolGroup(listOf(toolGroupMsg("t2", "write_file", time = 2000))),
        )
        val folded = foldToolCalls(items, viewMode = "chat")
        assertEquals(3, folded.size)
    }

    @Test
    fun `foldToolCalls does not change non chat viewMode`() {
        val items = listOf(
            MessageGroup.ToolGroup(listOf(toolGroupMsg("t1", "read_file"))),
            MessageGroup.ToolGroup(listOf(toolGroupMsg("t2", "write_file"))),
        )
        val folded = foldToolCalls(items, viewMode = "trace")
        assertEquals(2, folded.size)
    }

    // ---- Source scan tests ----

    @Test
    fun `WorkspaceActivity has no ChatGoalLine`() {
        val text = source("WorkspaceActivity.kt").readText()
        assertFalse("WorkspaceActivity should not contain ChatGoalLine", text.contains("ChatGoalLine("))
    }

    @Test
    fun `ChatFeed has no sticky-task-summary item key`() {
        val text = source("ChatFeed.kt").readText()
        assertFalse("ChatFeed should not contain sticky-task-summary", text.contains("sticky-task-summary"))
    }
}
