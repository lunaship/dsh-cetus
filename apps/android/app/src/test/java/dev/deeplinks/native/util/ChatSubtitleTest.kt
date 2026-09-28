package dev.deeplinks.native.util

import dev.deeplinks.core.L
import dev.deeplinks.native.MobileSessionActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话页顶栏副标题（2026-09-28 重设计 · 稿 03/10）。
 * 平时写「工作区 · 电脑名」；执行中换成「◌ 正在执行 · 第 N 步 · M 分钟」。
 */
class ChatSubtitleTest {

    @Test
    fun idleShowsWorkspaceAndHost() {
        assertEquals("dsh-links · Mac mini", chatTopSubtitle(running = false, workspaceLabel = "dsh-links", hostName = "Mac mini"))
    }

    @Test
    fun idleFallsBackToWhicheverSideExists() {
        assertEquals("Mac mini", chatTopSubtitle(false, null, "Mac mini"))
        assertEquals("dsh-links", chatTopSubtitle(false, "dsh-links", "  "))
        assertEquals(null, chatTopSubtitle(false, "", ""))
    }

    @Test
    fun runningShowsStepAndMinutes() {
        val text = chatTopSubtitle(
            running = true,
            workspaceLabel = "dsh-links",
            hostName = "Mac mini",
            activity = MobileSessionActivity(kind = "tool", label = "go test ./...", step = 12),
            elapsedSec = 185,
        )
        assertTrue(text!!.startsWith("◌ "))
        assertTrue(text.contains(L.homeStepLabel.format(12)))
        assertTrue(text.contains(L.minutesShort.format(3)))
    }

    /** 没有 activity（旧插件）或步号时省略那一段，但不写「0 分钟」。 */
    @Test
    fun runningOmitsMissingBits() {
        val noActivity = chatTopSubtitle(true, "dsh-links", "Mac mini", activity = null, elapsedSec = 0)
        assertEquals("◌ ${L.chatExecuting}", noActivity)

        val stepOnly = chatTopSubtitle(true, null, "Mac mini", activity = MobileSessionActivity(kind = "tool", step = 3), elapsedSec = 50)
        assertTrue(stepOnly!!.contains(L.homeStepLabel.format(3)))
        assertEquals(false, stepOnly.contains(L.minutesShort.format(0)))
    }
}
