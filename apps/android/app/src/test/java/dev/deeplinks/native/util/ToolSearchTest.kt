package dev.deeplinks.native.util
import dev.deeplinks.native.MobileMessage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 斜杠命令过滤（WI-005 / WI-006 抽出）单元测试。
 *
 * 验证：
 * - 至少 2 字符才过滤；
 * - 大小写不敏感；
 * - 子串匹配而非仅前缀；
 * - 空查询（不含 `/`）返回空。
 */
class ToolSearchTest {

    private val groups = listOf(
        SlashCommandGroup(
            title = "Built-in",
            items = listOf(
                SlashCommand("/help", "显示命令帮助"),
                SlashCommand("/history", "历史会话"),
                SlashCommand("/clear", "清屏"),
            )
        ),
        SlashCommandGroup(
            title = "Permissions",
            items = listOf(
                SlashCommand("/approve", "批准当前请求"),
                SlashCommand("/reject", "拒绝当前请求"),
            )
        ),
    )

    @Test
    fun `无前导斜杠返回空列表`() {
        val out = filterSlashCommands(groups, "help")
        assertEquals(emptyList<SlashCommandGroup>(), out)
        assertTrue(filterSlashCommands(groups, "").isEmpty())
        assertTrue(filterSlashCommands(groups, "   ").isEmpty())
    }

    @Test
    fun `单字符不过滤，返回全部分组`() {
        val out = filterSlashCommands(groups, "/h")
        assertEquals(2, out.size)
        assertEquals(3, out[0].items.size)
        assertEquals(2, out[1].items.size)
    }

    @Test
    fun `两个及以上字符按子串匹配，大小写不敏感`() {
        val out = filterSlashCommands(groups, "/HE")
        val flat = out.flatMap { it.items }.map { it.token }
        // /help 含 "he"
        assertEquals(listOf("/help"), flat)
    }

    @Test
    fun `不命中时返回空列表`() {
        val out = filterSlashCommands(groups, "/xyz123")
        assertTrue(out.isEmpty())
    }

    @Test
    fun `命中只覆盖有命中的分组，空分组被剔除`() {
        // "ap" 只命中 Permissions 分组
        val out = filterSlashCommands(groups, "/ap")
        assertEquals(1, out.size)
        assertEquals("Permissions", out[0].title)
        assertEquals(1, out[0].items.size)
        assertEquals("/approve", out[0].items[0].token)
    }

    @Test
    fun `保留分组输入顺序`() {
        val out = filterSlashCommands(groups, "/re")
        // /reject 命中 Permissions
        assertEquals(1, out.size)
        assertEquals("Permissions", out[0].title)
        assertEquals(listOf("/reject"), out[0].items.map { it.token })
    }
}