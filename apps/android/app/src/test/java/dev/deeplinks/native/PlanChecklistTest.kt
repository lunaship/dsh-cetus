package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Test

class PlanChecklistTest {
    @Test
    fun `collapsed line names the in-progress item`() {
        val items = listOf(
            MobileTodoItem("读方案", "completed"),
            MobileTodoItem("修登录", "in_progress"),
            MobileTodoItem("写测试", "pending"),
        )
        assertEquals(
            "1/3 · 正在：修登录",
            planCollapsedText(items, "%1\$d/%2\$d", "%1\$d/%2\$d · 正在：%3\$s"),
        )
    }

    @Test
    fun `no in-progress item omits the current clause`() {
        val items = listOf(MobileTodoItem("读方案", "done"), MobileTodoItem("写测试", "todo"))
        assertEquals("1/2", planCollapsedText(items, "%1\$d/%2\$d", "%1\$d/%2\$d · 正在：%3\$s"))
    }

    @Test
    fun `status words share the message todo vocabulary`() {
        assertEquals(PlanItemKind.Active, planItemKind("running"))
        assertEquals(PlanItemKind.Active, planItemKind("active"))
        assertEquals(PlanItemKind.Done, planItemKind("complete"))
        assertEquals(PlanItemKind.Pending, planItemKind("todo"))
        assertEquals(PlanItemKind.Pending, planItemKind("unknown"))
    }

    @Test
    fun `latest todo message wins and older lists are ignored`() {
        val messages = listOf(
            MobileMessage(id = "1", role = "todo", text = "", todos = listOf(MobileTodoItem("旧", "pending"))),
            MobileMessage(id = "2", role = "assistant", text = "hi"),
            MobileMessage(id = "3", role = "todo", text = "", todos = listOf(MobileTodoItem("新", "done"))),
        )
        assertEquals(listOf(MobileTodoItem("新", "done")), latestPlanItems(messages))
        assertEquals(emptyList<MobileTodoItem>(), latestPlanItems(emptyList()))
    }
}
