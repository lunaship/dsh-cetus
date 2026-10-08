package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方案 §18「通知」：状态可信、去重。
 *
 * [DshNotifier] 的「审批已处理」幂等窗口以前只有 4 秒魔法数字，没有测试。这里用注入的
 * 时钟把行为钉死：迟到的取消被压住、窗口过后恢复取消、重复处理时旧回调不吃掉新窗口。
 */
class ApprovalIdempotencyTest {

    @Test
    fun windowIsFourSecondsFromNow() {
        assertEquals(4_000L, ApprovalIdempotency.WINDOW_MS)
        assertEquals(10_000L, ApprovalIdempotency.deadline(now = 6_000L))
    }

    @Test
    fun noRecordMeansCancelProceeds() {
        // 从没处理过 → 正常取消（不能在窗口外把取消也压住，否则通知永远清不掉）
        assertFalse(ApprovalIdempotency.shouldSuppressCancel(deadline = null, now = 1_000L))
    }

    @Test
    fun cancelIsSuppressedInsideTheWindow() {
        val deadline = ApprovalIdempotency.deadline(now = 1_000L) // → 5_000
        assertTrue(ApprovalIdempotency.shouldSuppressCancel(deadline, now = 1_001L))
        assertTrue(ApprovalIdempotency.shouldSuppressCancel(deadline, now = 4_999L))
    }

    @Test
    fun cancelResumesAfterTheWindow() {
        val deadline = ApprovalIdempotency.deadline(now = 1_000L)
        // 边界：到期时刻本身不再压住（`now < deadline` 为 false）
        assertFalse(ApprovalIdempotency.shouldSuppressCancel(deadline, now = 5_000L))
        assertFalse(ApprovalIdempotency.shouldSuppressCancel(deadline, now = 9_000L))
    }

    @Test
    fun expiryOnlyAppliesWhenTheRecordedWindowIsUnchanged() {
        val first = ApprovalIdempotency.deadline(now = 1_000L) // 5_000
        // 期间没人再处理 → 旧回调正常生效
        assertTrue(ApprovalIdempotency.shouldApplyExpiry(recorded = first, expired = first))
    }

    @Test
    fun staleExpiryDoesNotEatANewerWindow() {
        val first = ApprovalIdempotency.deadline(now = 1_000L) // 5_000
        val second = ApprovalIdempotency.deadline(now = 2_000L) // 6_000
        // 用户在窗口内又处理了一次，记录已更新为 second；first 的延时回调不该取消掉新反馈。
        assertFalse(ApprovalIdempotency.shouldApplyExpiry(recorded = second, expired = first))
        assertTrue(ApprovalIdempotency.shouldApplyExpiry(recorded = second, expired = second))
    }

    @Test
    fun expiryDoesNothingWhenAlreadyCleared() {
        // 记录已被清掉（例如 cancelApproval 之后 remove）→ 不再重复 cancel
        assertFalse(ApprovalIdempotency.shouldApplyExpiry(recorded = null, expired = 5_000L))
    }
}
