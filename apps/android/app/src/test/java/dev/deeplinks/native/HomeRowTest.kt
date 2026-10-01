package dev.deeplinks.native

import dev.deeplinks.core.L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 首页会话行两层文字（2026-10-02 Lody 简化 3.2）。
 *
 * 规则：元信息行 = 工作区 · 状态（仅执行中 / 等你批准 / 已中断）· 步数，时间由 UI 右对齐；
 * 结果一句话 = lastResult.text（L7：文件数只认改动卡，不写进行里）。完成态无状态点
 * ——homeRowTexts 不输出状态词，UI 不画点。
 */
class HomeRowTest {

    private fun session(
        running: Boolean = false,
        awaiting: Boolean = false,
        activity: MobileSessionActivity? = null,
        lastResult: MobileSessionResult? = null,
        stoppedReason: String? = null,
    ) = MobileSession(
        sessionId = "s1",
        title = "会话",
        updatedAt = 0L,
        running = running,
        blank = false,
        cwd = "/Users/me/project",
        agentPreset = null,
        awaitingInput = awaiting,
        activity = activity,
        lastResult = lastResult,
        stoppedReason = stoppedReason,
    )

    @Test
    fun metaCarriesWorkspaceActivityAndStep() {
        val texts = homeRowTexts(
            session(running = true, activity = MobileSessionActivity(kind = "tool", label = "go test ./...", step = 12)),
            goalSummary = null,
        )
        assertEquals(
            "project · " + L.homeRunningInline.format("go test ./...") + " · " + L.homeStepLabel.format(12),
            texts.meta,
        )
        assertNull(texts.result)
    }

    @Test
    fun runningWithoutActivityFallsBackToGoalThenRunningLabel() {
        assertEquals("project · 修一下登录", homeRowTexts(session(running = true), "修一下登录").meta)
        assertEquals("project · " + L.runningStatus, homeRowTexts(session(running = true), null).meta)
    }

    @Test
    fun awaitingRowSaysWaitingApproval() {
        assertEquals("project · " + L.homeChipWaitingApproval, homeRowTexts(session(awaiting = true, running = true), null).meta)
    }

    @Test
    fun doneRowHasNoStatusWordAndResultGoesToSecondLayer() {
        val texts = homeRowTexts(session(lastResult = MobileSessionResult(text = "门禁全绿", files = 79)), null)
        // 完成态：元信息只有工作区（无状态词 → UI 无状态点），文件数不进行里（L7）
        assertEquals("project", texts.meta)
        assertEquals("门禁全绿", texts.result)
    }

    @Test
    fun interruptedRowSaysItInMetaNotInResult() {
        val texts = homeRowTexts(
            session(stoppedReason = "interrupted", lastResult = MobileSessionResult(text = "跑到一半")),
            null,
        )
        assertEquals("project · " + stoppedReasonLabel("interrupted"), texts.meta)
        assertEquals("跑到一半", texts.result)
    }

    @Test
    fun doneRowWithoutAnyResultKeepsQuietMeta() {
        val texts = homeRowTexts(session(), null)
        assertEquals("project", texts.meta)
        assertNull(texts.result)
    }

    /** 稿 08：离线时进行中行是缓存状态，元信息加「最后看到：」前缀。 */
    @Test
    fun offlineRunningRowIsPrefixedWithLastSeen() {
        val texts = homeRowTexts(
            session(running = true, activity = MobileSessionActivity(kind = "tool", label = "go test ./...", step = 12)),
            goalSummary = null,
            offline = true,
        )
        assertEquals(
            "project · " + L.homeLastSeenPrefix + L.homeRunningInline.format("go test ./...") + " · " + L.homeStepLabel.format(12),
            texts.meta,
        )
    }

    /** 离线只影响进行中行：最近行不加前缀。 */
    @Test
    fun offlineDoesNotPrefixRecentRows() {
        val texts = homeRowTexts(session(lastResult = MobileSessionResult(text = "门禁全绿")), null, offline = true)
        assertEquals("project", texts.meta)
        assertEquals("门禁全绿", texts.result)
    }
}
