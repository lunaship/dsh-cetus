package dev.deeplinks.native

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K3 聚焦令牌策略回归：只有对话页/输入框可见的来源才发令牌；
 * 首页左滑归档当前会话（[ComposerFocusSource.ArchiveOnHome]）绝不在输入框未组合时请求聚焦。
 */
class ComposerFocusTest {

    @Test
    fun `归档当前会话不发聚焦令牌`() {
        assertFalse(composerFocusShouldEmit(ComposerFocusSource.ArchiveOnHome))
    }

    @Test
    fun `新任务草稿 通知回复 命令插入都发令牌`() {
        assertTrue(composerFocusShouldEmit(ComposerFocusSource.NewTaskDraft))
        assertTrue(composerFocusShouldEmit(ComposerFocusSource.NotificationReply))
        assertTrue(composerFocusShouldEmit(ComposerFocusSource.CommandInsert))
    }
}
