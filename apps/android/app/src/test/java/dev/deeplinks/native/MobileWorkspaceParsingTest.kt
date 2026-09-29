package dev.deeplinks.native

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileWorkspaceParsingTest {
    @Test
    fun parsesCommittedWorkspaceCreateEcho() {
        val parsed = parseMobileWorkspace(
            JSONObject()
                .put("workspaceId", "workspace-1")
                .put("path", "/Volumes/Space/Dev/demo/")
                .put("title", "Demo")
                .put("sessionIds", JSONArray().put("s1").put("s2")),
        )

        assertEquals(
            MobileWorkspace(
                workspaceId = "workspace-1",
                path = "/Volumes/Space/Dev/demo",
                title = "Demo",
                sessionIds = listOf("s1", "s2"),
            ),
            parsed,
        )
    }

    @Test
    fun rejectsWorkspaceWithoutUsablePath() {
        assertNull(parseMobileWorkspace(JSONObject()))
    }

    @Test
    fun parseMobileSession_jsonNullAgentPresetIsAbsent() {
        val parsed = parseMobileSession(
            JSONObject()
                .put("sessionId", "s1")
                .put("title", "Reply with exactly PONG015")
                .put("agentPreset", JSONObject.NULL)
                .put("cwd", JSONObject.NULL),
        )
        assertNull(parsed.agentPreset)
        assertNull(parsed.cwd)
        assertEquals("Reply with exactly PONG015", parsed.title)
    }

    @Test
    fun parseMobileSession_literalNullAgentPresetIsAbsent() {
        val parsed = parseMobileSession(
            JSONObject()
                .put("sessionId", "s1")
                .put("agentPreset", "null"),
        )
        assertNull(parsed.agentPreset)
        assertEquals("未命名会话", parsed.title)
    }

    /** 旧插件不下发 activity / lastResult：解析成 null，首页走「运行中 / 已完成」回退。 */
    @Test
    fun parseMobileSession_missingActivityAndLastResultFallBackToNull() {
        val parsed = parseMobileSession(
            JSONObject().put("sessionId", "s1").put("running", true),
        )
        assertNull(parsed.activity)
        assertNull(parsed.lastResult)
    }

    @Test
    fun parseMobileSession_parsesActivity() {
        val parsed = parseMobileSession(
            JSONObject()
                .put("sessionId", "s1")
                .put("running", true)
                .put(
                    "activity",
                    JSONObject()
                        .put("kind", "tool")
                        .put("label", "go test ./...")
                        .put("step", 12)
                        .put("startedAt", 1_759_000_000_000L),
                ),
        )
        assertEquals("tool", parsed.activity?.kind)
        assertEquals("go test ./...", parsed.activity?.label)
        assertEquals(12L, parsed.activity?.step)
        assertEquals(1_759_000_000_000L, parsed.activity?.startedAt)
        assertTrue(parsed.activity?.isTool == true)
    }

    /** thinking / writing 没有 label 与 step，仍要解析出 kind。 */
    @Test
    fun parseMobileSession_parsesActivityWithoutLabel() {
        val parsed = parseMobileSession(
            JSONObject()
                .put("sessionId", "s1")
                .put("running", true)
                .put("activity", JSONObject().put("kind", "thinking")),
        )
        assertEquals("thinking", parsed.activity?.kind)
        assertNull(parsed.activity?.label)
        assertNull(parsed.activity?.step)
        assertEquals(false, parsed.activity?.isTool)
    }

    @Test
    fun parseMobileSession_parsesLastResult() {
        val parsed = parseMobileSession(
            JSONObject()
                .put("sessionId", "s1")
                .put("running", false)
                .put(
                    "lastResult",
                    JSONObject()
                        .put("text", "门禁全绿")
                        .put("files", 79)
                        .put("added", 1200)
                        .put("deleted", 300),
                ),
        )
        assertEquals("门禁全绿", parsed.lastResult?.text)
        assertEquals(79L, parsed.lastResult?.files)
        assertEquals(1200L, parsed.lastResult?.added)
        assertEquals(300L, parsed.lastResult?.deleted)
    }

    /** 只有改动数字、没有文本时也要保留。 */
    @Test
    fun parseMobileSession_parsesLastResultWithoutText() {
        val parsed = parseMobileSession(
            JSONObject()
                .put("sessionId", "s1")
                .put("lastResult", JSONObject().put("files", 6)),
        )
        assertNull(parsed.lastResult?.text)
        assertEquals(6L, parsed.lastResult?.files)
    }

    /** 插件已经把空字段省掉了；真收到空对象时也不该造出一个全空的结果。 */
    @Test
    fun parseMobileSession_emptyLastResultIsNull() {
        val parsed = parseMobileSession(
            JSONObject().put("sessionId", "s1").put("lastResult", JSONObject()),
        )
        assertNull(parsed.lastResult)
    }

    /** activity 没有 kind（不该出现，但旧/异常服务端可能有）时视为没有。 */
    @Test
    fun parseMobileSession_activityWithoutKindIsNull() {
        val parsed = parseMobileSession(
            JSONObject().put("sessionId", "s1").put("activity", JSONObject().put("label", "go test")),
        )
        assertNull(parsed.activity)
    }

    @Test
    fun resolveHarnessLabel_fallsBackWhenPresetIsJsonNullLiteral() {
        assertEquals(
            "标准模式",
            resolveHarnessLabel(
                presets = emptyList(),
                activeId = "null",
                settingsId = "",
                fallback = "标准模式",
            ),
        )
        assertEquals(
            "标准",
            resolveHarnessLabel(
                presets = listOf(MobileAgentPreset("standard", "标准")),
                activeId = null,
                settingsId = "standard",
                fallback = "标准模式",
            ),
        )
    }

    @Test
    fun builtinPresets_useLocalizedNameAndDescription() {
        assertEquals("PTC 模式", resolveHarnessLabel(listOf(MobileAgentPreset("ptc", "ptc")), null, "ptc", "标准模式"))
        assertEquals("创造模式", presetDisplayName("cordis", ""))
        assertEquals("动效制作模式", presetDisplayName("motion-graphics-zh-mode", "动效制作模式"))
        assertEquals("my-mode", presetDisplayName("my-mode", null))
        assertTrue(presetDisplayDescription("minimal", "terminal only").startsWith("Agent 仅使用终端工具"))
        assertEquals("自定义说明", presetDisplayDescription("my-mode", "自定义说明"))
    }

    @Test
    fun parseHistoryFiles_reads_string_array() {
        val files = parseHistoryFiles(
            JSONObject().put("files", JSONArray().put("notes/hi.md").put("shot.png")),
        )
        assertEquals(listOf("notes/hi.md", "shot.png"), files)
    }

    @Test
    fun parseHistoryFiles_skips_blank() {
        assertEquals(emptyList<String>(), parseHistoryFiles(JSONObject()))
    }
}
