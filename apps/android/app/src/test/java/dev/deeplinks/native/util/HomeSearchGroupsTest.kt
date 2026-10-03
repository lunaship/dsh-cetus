package dev.deeplinks.native.util

import dev.deeplinks.native.MobileSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v4 2.4：搜索结果按「标题匹配 / 内容匹配」分组，组内按更新时间倒序。 */
class HomeSearchGroupsTest {

    private fun session(id: String, title: String, updatedAt: Long) =
        MobileSession(sessionId = id, title = title, updatedAt = updatedAt, running = false, blank = false, cwd = "/w/p", agentPreset = null)

    @Test
    fun titleHitsFirstThenContentHitsWithSnippet() {
        val sessions = listOf(
            session("a", "完善审批状态同步", 10),
            session("b", "中继握手重写", 30),
            session("c", "通知栏直接审批的风险评估", 20),
        )
        val groups = homeSearchGroups(sessions, "审批", mapOf("b" to "手机端不提供审批端口"))
        assertEquals(listOf("c", "a"), groups.titleMatches.map { it.sessionId })
        assertEquals(listOf("b"), groups.contentMatches.map { it.first.sessionId })
        assertEquals("手机端不提供审批端口", groups.contentMatches.single().second)
    }

    @Test
    fun blankNeedleListsEverythingAsTitleGroup() {
        val groups = homeSearchGroups(listOf(session("a", "x", 1), session("a", "x", 2)), " ", emptyMap())
        assertEquals(1, groups.titleMatches.size)
        assertEquals(0, groups.contentMatches.size)
    }

    @Test
    fun missingSnippetStaysNull() {
        val groups = homeSearchGroups(listOf(session("a", "x", 1)), "zzz", mapOf("a" to " "))
        assertNull(groups.contentMatches.single().second)
    }
}
