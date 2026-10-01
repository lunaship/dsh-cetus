package dev.deeplinks.native.util

import dev.deeplinks.native.MobileMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 建议行显示条件真值表（2026-10-02 Lody 简化 4.3 / 7.1）：
 * 对话视图 · 在线 · 未在执行 · 最后一个分组是已结束的助手回复 · 输入为空 · 无待处理审批 / 提问。
 */
class ComposerSuggestionsTest {

    private val allGood = mapOf(
        "viewMode" to "chat",
        "running" to false,
        "lastGroupEndedAssistant" to true,
        "inputBlank" to true,
        "pendingBlocked" to false,
        "online" to true,
    )

    private fun visible(vararg overrides: Pair<String, Any>): Boolean {
        val m = allGood.toMutableMap().apply { putAll(overrides) }
        return composerSuggestionVisible(
            viewMode = m["viewMode"] as String,
            running = m["running"] as Boolean,
            lastGroupEndedAssistant = m["lastGroupEndedAssistant"] as Boolean,
            inputBlank = m["inputBlank"] as Boolean,
            pendingBlocked = m["pendingBlocked"] as Boolean,
            online = m["online"] as Boolean,
        )
    }

    @Test
    fun `条件全部满足时显示`() {
        assertTrue(visible())
    }

    @Test
    fun `执行中不显示`() {
        assertFalse(visible("running" to true))
    }

    @Test
    fun `输入非空不显示`() {
        assertFalse(visible("inputBlank" to false))
    }

    @Test
    fun `有待处理审批或提问不显示`() {
        assertFalse(visible("pendingBlocked" to true))
    }

    @Test
    fun `离线不显示`() {
        assertFalse(visible("online" to false))
    }

    @Test
    fun `轨迹视图不显示`() {
        assertFalse(visible("viewMode" to "trace"))
    }

    @Test
    fun `最后一个分组不是已结束的助手回复不显示`() {
        assertFalse(visible("lastGroupEndedAssistant" to false))
        assertFalse(visible("running" to true, "lastGroupEndedAssistant" to true))
    }

    @Test
    fun `lastGroupEndedAssistant 判定`() {
        val ended = MobileMessage(id = "a", role = "assistant", text = "done")
        val running = MobileMessage(id = "b", role = "assistant", text = "…", running = true)
        val user = MobileMessage(id = "u", role = "user", text = "hi")
        assertTrue(lastGroupEndedAssistant(listOf(user, ended), running = false))
        assertFalse(lastGroupEndedAssistant(listOf(user, ended), running = true))
        assertFalse(lastGroupEndedAssistant(listOf(user, running), running = true))
        assertFalse(lastGroupEndedAssistant(listOf(user), running = false))
        assertFalse(lastGroupEndedAssistant(emptyList(), running = false))
    }

    @Test
    fun `菜单首项文字随视图切换`() {
        assertEquals("查看轨迹", viewModeToggleLabel("chat", "查看轨迹", "返回对话"))
        assertEquals("返回对话", viewModeToggleLabel("trace", "查看轨迹", "返回对话"))
    }
}
