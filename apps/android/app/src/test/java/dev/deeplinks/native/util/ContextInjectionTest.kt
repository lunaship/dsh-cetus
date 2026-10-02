package dev.deeplinks.native.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ContextInjectionTest {
    @Test
    fun `skill catalog reminder is context injection`() {
        val text = """
            <system-reminder>
            The available skill catalog changed. This complete catalog replaces every earlier available-skills list in this session:
            <available_skills>
            agent-reach: 全网调研
            </available_skills>
            </system-reminder>
        """.trimIndent()
        assertTrue(isContextInjectionText(text))
        assertEquals(listOf("skill-catalog"), contextInjectionLabels(text))
    }

    @Test
    fun `plain user hello is not injection`() {
        assertFalse(isContextInjectionText("你好"))
        assertFalse(isContextInjectionText(""))
    }

    @Test
    fun `html-escaped reminder still counts`() {
        assertTrue(isContextInjectionText("&lt;system-reminder&gt;hi&lt;/system-reminder&gt;"))
    }

    @Test
    fun `runtime context block is context injection`() {
        val text = """
            Current runtime context:
            - Host OS: macOS
            - Current DSH file policy: danger-full-access
            - Approval prompts are disabled
        """.trimIndent()
        assertTrue(isContextInjectionText(text))
        assertTrue(contextInjectionLabels(text).contains("runtime"))
        assertTrue(contextInjectionLabels(text).contains("file-policy"))
    }

    @Test
    fun `goal round prompt is collapsed injection`() {
        val text = """
            <goal_round>
            Objective: "直接复刻我本地的 hermes bot 的代码到 Hermes-perch 作为一个新项目"
            Round: 1/256

            Continue working toward the objective in this same session.
        """.trimIndent()
        assertTrue(isGoalRoundText(text))
        assertTrue(isContextInjectionText(text))
        assertEquals("直接复刻我本地的 hermes bot 的代码到 Hermes-perch 作为一个新项目", goalRoundObjective(text))
        assertEquals("1/256", goalRoundProgress(text))
    }

    @Test
    fun `goal round helpers tolerate missing fields`() {
        assertFalse(isGoalRoundText("普通用户消息"))
        assertEquals(null, goalRoundObjective("<goal_round>Round: 2/8</goal_round>"))
        assertEquals(null, goalRoundProgress("<goal_round>Objective: \"x\"</goal_round>"))
    }

    @Test
    fun `shared context injection cases match the plugin`() {
        val file = sharedCasesFile()
        val cases = JSONObject(file.readText()).getJSONArray("cases")
        assertTrue("用例清单太短，可能没读到文件", cases.length() >= 10)
        for (i in 0 until cases.length()) {
            val item = cases.getJSONObject(i)
            assertEquals(
                item.getString("name"),
                item.getBoolean("injection"),
                isContextInjectionText(item.getString("text")),
            )
        }
    }

    private fun sharedCasesFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testdata/context-injection-cases.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("找不到 testdata/context-injection-cases.json")
    }
}
