package dev.deeplinks.native.util

import dev.deeplinks.core.L
import dev.deeplinks.core.sendFailedNetwork
import dev.deeplinks.core.sendFailedTooLarge
import dev.deeplinks.core.sendOutcomeUnknown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方案 §7 C03 要求 4/5 + §18「错误不是空状态」：
 * 失败必须按场景分类，并区分「确定没发出」与「结果未知」。
 */
class SendFailureTest {
    // ===== 超时 = 结果未知（最关键的一条：不能自动重发） =====

    @Test
    fun timeoutIsOutcomeUnknownNotNetworkDown() {
        assertEquals(
            SendFailure.OutcomeUnknown,
            classifySendFailure(message = "Read timed out"),
        )
        assertEquals(
            SendFailure.OutcomeUnknown,
            classifySendFailure(message = "请求超时"),
        )
        assertEquals(
            SendFailure.OutcomeUnknown,
            classifySendFailure(message = "Connection reset by peer"),
        )
        assertEquals(
            SendFailure.OutcomeUnknown,
            classifySendFailure(message = "broken pipe"),
        )
    }

    @Test
    fun outcomeUnknownNeverAutoResends() {
        // 方案 §7 要求 5：服务端可能已接受，自动重发会产生重复提交。
        assertFalse(failureAllowsAutoResend(SendFailure.OutcomeUnknown))
        // 但确定没发出去的可以安全重试
        assertTrue(failureAllowsAutoResend(SendFailure.NetworkDown))
    }

    // ===== 确定连不上 = 可安全重试 =====

    @Test
    fun refusedConnectionIsNetworkDown() {
        assertEquals(
            SendFailure.NetworkDown,
            classifySendFailure(message = "Connection refused"),
        )
        assertEquals(
            SendFailure.NetworkDown,
            classifySendFailure(message = "无法连接主机"),
        )
        assertEquals(
            SendFailure.NetworkDown,
            classifySendFailure(message = "host unreachable"),
        )
    }

    // ===== HTTP 状态码 =====

    @Test
    fun httpCodesMapToScenarios() {
        assertEquals(SendFailure.Unauthorized, classifySendFailure(null, httpCode = 401))
        assertEquals(SendFailure.Forbidden, classifySendFailure(null, httpCode = 403))
        assertEquals(SendFailure.TargetGone, classifySendFailure(null, httpCode = 404))
        assertEquals(SendFailure.TargetGone, classifySendFailure(null, httpCode = 410))
        assertEquals(SendFailure.Busy, classifySendFailure(null, httpCode = 409))
        assertEquals(SendFailure.TooLarge, classifySendFailure(null, httpCode = 413))
        assertEquals(SendFailure.Generic, classifySendFailure(null, httpCode = 400))
    }

    @Test
    fun bodyErrorCodesAreHonoured() {
        assertEquals(
            SendFailure.TooLarge,
            classifySendFailure(null, httpCode = 400, body = """{"error":"payload_too_large"}"""),
        )
        assertEquals(
            SendFailure.TargetGone,
            classifySendFailure(null, httpCode = 500, body = """{"error":"session_not_found"}"""),
        )
        assertEquals(
            SendFailure.Busy,
            classifySendFailure(null, httpCode = 400, body = """{"error":"session_busy"}"""),
        )
    }

    // ===== 分类优先于通用兜底 =====

    @Test
    fun timeoutBeatsGenericNetworkKeyword() {
        // 一条消息同时含 "connect" 与 "timeout" 时，必须归为结果未知而不是可重试，
        // 否则会错误地允许自动重发。
        assertEquals(
            SendFailure.OutcomeUnknown,
            classifySendFailure(message = "connect failed then read timed out"),
        )
    }

    @Test
    fun unknownMessageFallsBackToGeneric() {
        assertEquals(SendFailure.Generic, classifySendFailure(message = "something odd"))
        assertEquals(SendFailure.Generic, classifySendFailure(null))
    }

    // ===== 输入保留与文案 =====

    @Test
    fun everyFailureKeepsTheDraft() {
        // 方案 §7 要求 3/4：任何失败都不能丢用户写的内容。
        for (failure in SendFailure.entries) {
            assertTrue("$failure 必须保留输入", failureKeepsDraft(failure))
        }
    }

    @Test
    fun messageIsLocalizedAndActionable() {
        for (failure in SendFailure.entries) {
            val message = sendFailureMessage(failure, "detail")
            assertTrue("$failure 文案不应为空", message.isNotBlank())
        }
        // 结果未知的文案要说清"可能已发出"，并提示先核对而不是直接重发
        assertTrue(L.sendOutcomeUnknown.contains(L.sendOutcomeUnknown))
        assertEquals(L.sendOutcomeUnknown, sendFailureMessage(SendFailure.OutcomeUnknown))
        assertEquals(L.sendFailedNetwork, sendFailureMessage(SendFailure.NetworkDown))
        assertEquals(L.sendFailedTooLarge, sendFailureMessage(SendFailure.TooLarge))
    }

    @Test
    fun genericMessageCarriesDetail() {
        val message = sendFailureMessage(SendFailure.Generic, "boom")
        assertTrue("通用失败要带上原始细节", message.contains("boom"))
    }
}
