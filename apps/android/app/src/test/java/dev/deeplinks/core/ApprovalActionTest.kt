package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 审批通知 action 列表的纯函数测试（项目未引入 Robolectric，用 buildList 抽成纯函数）。
 */
class ApprovalActionTest {

    @Test
    fun `default settings - no actions, only open the app`() {
        val kinds = approvalActionKinds(quickApprove = false, sdkInt = 34)
        assertEquals(emptyList<Boolean>(), kinds)
    }

    @Test
    fun `quick approve on API 34 - reject and allow once`() {
        val kinds = approvalActionKinds(quickApprove = true, sdkInt = 34)
        assertEquals(listOf(false, true), kinds)
    }

    @Test
    fun `quick approve on API 30 - no actions`() {
        val kinds = approvalActionKinds(quickApprove = true, sdkInt = 30)
        assertEquals(emptyList<Boolean>(), kinds)
    }

    @Test
    fun `quick approve on API 31 - reject and allow once`() {
        val kinds = approvalActionKinds(quickApprove = true, sdkInt = 31)
        assertEquals(listOf(false, true), kinds)
    }

    /** 接管已关闭：审批已交回电脑网页，旧通知上的「允许」不许生效，直接收回。 */
    @Test
    fun `takeover off - allow cancels notification`() {
        assertEquals(
            ApprovalReceiverDecision.Cancel,
            approvalReceiverDecision(
                approve = true,
                backgroundTakeover = false,
                quickApprove = true,
                locked = false,
            ),
        )
    }

    @Test
    fun `reject answers service even when locked`() {
        assertEquals(
            ApprovalReceiverDecision.Answer,
            approvalReceiverDecision(
                approve = false,
                backgroundTakeover = true,
                quickApprove = false,
                locked = true,
            ),
        )
    }

    /** 通知栏直批开关关着（多见于关开关前弹出的旧通知）：提示去 App 内确认，不批准。 */
    @Test
    fun `allow with quick approve off - confirm in app`() {
        assertEquals(
            ApprovalReceiverDecision.ConfirmInApp,
            approvalReceiverDecision(
                approve = true,
                backgroundTakeover = true,
                quickApprove = false,
                locked = false,
            ),
        )
    }

    @Test
    fun `allow quick approve on locked screen - needs unlock`() {
        assertEquals(
            ApprovalReceiverDecision.NeedsUnlock,
            approvalReceiverDecision(
                approve = true,
                backgroundTakeover = true,
                quickApprove = true,
                locked = true,
            ),
        )
    }

    @Test
    fun `allow quick approve unlocked - answers service`() {
        assertEquals(
            ApprovalReceiverDecision.Answer,
            approvalReceiverDecision(
                approve = true,
                backgroundTakeover = true,
                quickApprove = true,
                locked = false,
            ),
        )
    }
}
