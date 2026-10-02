package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionNoticeTest {
    private val labels = CompletionNoticeLabels(
        scheduleDone = "定时任务「%s」已完成",
        scheduleFailed = "定时任务「%s」失败",
        scheduleStopped = "定时任务「%s」已停止",
        taskDone = "任务「%s」已完成",
        taskFailed = "任务「%s」失败",
        taskStopped = "任务「%s」已停止",
        statusDone = "已完成",
        statusFailed = "失败",
        statusStopped = "已停止",
        bodyWithDuration = "%1\$s · 耗时 %2\$s",
        publicWithDuration = "DeepLinks · %1\$s · %2\$s",
        publicStatus = "DeepLinks · %s",
        duration = { "${it / 60_000}m" },
    )

    @Test
    fun `schedule completion notifies even when short`() {
        val notice = decide(
            origin = "schedule",
            state = "completed",
            durationMs = 10_000,
        )
        assertTrue(notice.notify)
        assertTrue(notice.schedule)
        assertEquals(CompletionNoticeKind.Done, notice.kind)
        val text = completionNoticeText("日报", notice, labels)
        assertEquals("定时任务「日报」已完成", text.title)
        assertEquals("已完成 · 耗时 0m", text.body)
        assertEquals("DeepLinks · 已完成 · 0m", text.publicText)
        assertNull(text.expanded)
    }

    @Test
    fun `schedule failure and stop use their own status`() {
        val failed = decide(origin = "schedule", state = "failed", durationMs = null)
        assertEquals(CompletionNoticeKind.Failed, failed.kind)
        assertNull(failed.durationMs)
        assertEquals("定时任务「日报」失败", completionNoticeText("日报", failed, labels).title)
        assertEquals("DeepLinks · 失败", completionNoticeText("日报", failed, labels).publicText)

        val stopped = decide(origin = "schedule", state = "stopped", durationMs = 120_000)
        assertEquals(CompletionNoticeKind.Stopped, stopped.kind)
        assertEquals("定时任务「日报」已停止", completionNoticeText("日报", stopped, labels).title)
    }

    @Test
    fun `ordinary tasks notify only after the threshold`() {
        assertFalse(decide(origin = "user", state = "completed", durationMs = 2 * 60_000).notify)
        assertFalse(decide(origin = "subagent", state = "completed", durationMs = null).notify)
        val reached = decide(origin = "user", state = "failed", durationMs = 3 * 60_000)
        assertTrue(reached.notify)
        assertEquals(CompletionNoticeKind.Failed, reached.kind)
        assertEquals("任务「修登录」失败", completionNoticeText("修登录", reached, labels).title)
    }

    @Test
    fun `master switch and non-terminal states stay quiet`() {
        assertFalse(
            decide(origin = "schedule", state = "completed", durationMs = 60_000, notifyOnDone = false).notify,
        )
        assertFalse(decide(origin = "schedule", state = "running", durationMs = 60_000).notify)
    }

    @Test
    fun `reply first line stays off the lock screen`() {
        val notice = decide(
            origin = "schedule",
            state = "completed",
            durationMs = 60_000,
            showReplyFirstLine = true,
            reply = "  门禁全绿  \n第二行",
        )
        assertEquals("门禁全绿", notice.privateFirstLine)
        val text = completionNoticeText("日报", notice, labels)
        assertEquals("门禁全绿", text.expanded)
        assertFalse(text.publicText.contains("门禁"))
        assertFalse(text.body.contains("门禁"))
        assertNull(
            decide(
                origin = "user",
                state = "completed",
                durationMs = 10 * 60_000,
                showReplyFirstLine = false,
                reply = "门禁全绿",
            ).privateFirstLine,
        )
    }

    @Test
    fun `blank reply and unknown minute values fall back`() {
        assertNull(replyFirstLine("  \n  "))
        assertEquals(3, normalizeLongTaskMinutes(0))
        assertEquals(10, normalizeLongTaskMinutes(10))
    }

    @Test
    fun `observed duration starts at the first active state`() {
        val tracker = ActiveSinceTracker()
        assertNull(tracker.finish("s1", 1_000))
        tracker.observe("s1", "running", 1_000)
        tracker.observe("s1", "awaitingApproval", 2_000)
        assertEquals(4_000L, tracker.finish("s1", 5_000))
        tracker.observe("s2", "running", 1_000)
        tracker.clear()
        assertNull(tracker.finish("s2", 9_000))
    }

    private fun decide(
        origin: String,
        state: String,
        durationMs: Long?,
        notifyOnDone: Boolean = true,
        showReplyFirstLine: Boolean = false,
        reply: String? = null,
        minutes: Int = 3,
    ) = decideCompletionNotice(
        origin = origin,
        state = state,
        observedDurationMs = durationMs,
        longTaskMinutes = minutes,
        notifyOnDone = notifyOnDone,
        showReplyFirstLine = showReplyFirstLine,
        replyFirstLine = reply,
    )
}
