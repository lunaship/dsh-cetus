package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 审批通知 action 列表的纯函数测试（项目未引入 Robolectric，用 buildList 抽成纯函数）。
 */
class ApprovalActionTest {

    @Test
    fun `default settings - only reject action`() {
        val kinds = approvalActionKinds(quickApprove = false, sdkInt = 34)
        assertEquals(listOf(false), kinds)
    }

    @Test
    fun `quick approve on API 34 - reject and allow once`() {
        val kinds = approvalActionKinds(quickApprove = true, sdkInt = 34)
        assertEquals(listOf(false, true), kinds)
    }

    @Test
    fun `quick approve on API 30 - only reject`() {
        val kinds = approvalActionKinds(quickApprove = true, sdkInt = 30)
        assertEquals(listOf(false), kinds)
    }

    @Test
    fun `quick approve on API 31 - reject and allow once`() {
        val kinds = approvalActionKinds(quickApprove = true, sdkInt = 31)
        assertEquals(listOf(false, true), kinds)
    }
}
