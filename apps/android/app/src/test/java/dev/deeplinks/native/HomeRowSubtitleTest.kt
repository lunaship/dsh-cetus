package dev.deeplinks.native

import dev.deeplinks.core.L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页会话行副标题（2026-09-28 重设计 · 方案 3.5/3.6/3.8）。
 *
 * 规则来自设计稿：进行中写 activity 推出来的当前步骤、最近写 lastResult 的结果一句话、
 * 等你处理写「这条审批在电脑端网页上处理」、离线时进行中行前面加「最后看到：」。
 * 数据缺失时按方案回退「运行中 / 已完成」。
 */
class HomeRowSubtitleTest {

    private fun session(
        running: Boolean = false,
        awaiting: Boolean = false,
        activity: MobileSessionActivity? = null,
        lastResult: MobileSessionResult? = null,
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
    )

    @Test
    fun runningWithToolActivityShowsCommandAndStep() {
        val text = homeRowSubtitle(
            session(running = true, activity = MobileSessionActivity(kind = "tool", label = "go test ./...", step = 12)),
            goalSummary = null,
        )
        assertEquals(L.homeRunningInline.format("go test ./...") + " · " + L.homeStepLabel.format(12), text)
    }

    @Test
    fun runningWithoutStepOmitsStepSuffix() {
        val text = homeRowSubtitle(
            session(running = true, activity = MobileSessionActivity(kind = "tool", label = "ls")),
            goalSummary = null,
        )
        assertEquals(L.homeRunningInline.format("ls"), text)
    }

    @Test
    fun runningThinkingAndWritingUseTheirOwnCopy() {
        assertEquals(
            L.homeThinking,
            homeRowSubtitle(session(running = true, activity = MobileSessionActivity(kind = "thinking")), null),
        )
        assertEquals(
            L.homeWriting,
            homeRowSubtitle(session(running = true, activity = MobileSessionActivity(kind = "writing")), null),
        )
    }

    /** 旧插件没有 activity：退回目标摘要；再没有就写「运行中」。 */
    @Test
    fun runningFallsBackToGoalThenToRunningLabel() {
        assertEquals("修一下登录", homeRowSubtitle(session(running = true), "修一下登录"))
        assertEquals(L.runningStatus, homeRowSubtitle(session(running = true), null))
    }

    @Test
    fun awaitingInputPointsAtTheDesktop() {
        assertEquals(L.homeApprovalOnDesktop, homeRowSubtitle(session(awaiting = true, running = true), null))
    }

    @Test
    fun recentCombinesFileCountAndSummary() {
        val text = homeRowSubtitle(
            session(lastResult = MobileSessionResult(text = "门禁全绿", files = 79)),
            null,
        )
        assertEquals(L.homeFilesChanged.format(79) + "，" + "门禁全绿", text)
    }

    @Test
    fun recentWithOnlyTextOrOnlyFilesStillReads() {
        assertEquals("门禁全绿", homeRowSubtitle(session(lastResult = MobileSessionResult(text = "门禁全绿")), null))
        assertEquals(
            L.homeFilesChanged.format(6),
            homeRowSubtitle(session(lastResult = MobileSessionResult(files = 6)), null),
        )
    }

    /** 方案 3.6：没有 lastResult 字段时写「已完成」。 */
    @Test
    fun recentWithoutAnyResultFallsBackToDone() {
        assertEquals(L.homeDoneFallback, homeRowSubtitle(session(), null))
        // files = 0 不算「改了 0 个文件」，同样回退
        assertEquals(L.homeDoneFallback, homeRowSubtitle(session(lastResult = MobileSessionResult(files = 0)), null))
    }

    /** 方案 3.8：离线时进行中行是缓存状态，前面加「最后看到：」。 */
    @Test
    fun offlineRunningRowIsPrefixedWithLastSeen() {
        val text = homeRowSubtitle(
            session(running = true, activity = MobileSessionActivity(kind = "tool", label = "go test ./...", step = 12)),
            goalSummary = null,
            offline = true,
        )
        assertTrue("was: $text", text.startsWith(L.homeLastSeenPrefix))
        assertTrue("was: $text", text.contains("go test ./..."))
    }

    /** 离线只影响进行中行：最近行不加前缀。 */
    @Test
    fun offlineDoesNotPrefixRecentRows() {
        val text = homeRowSubtitle(session(lastResult = MobileSessionResult(text = "门禁全绿")), null, offline = true)
        assertEquals("门禁全绿", text)
    }
}
