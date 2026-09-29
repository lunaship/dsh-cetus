package dev.deeplinks.native

import dev.deeplinks.core.DshStringsZh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class MobileAuthErrorTest {
    @Test
    fun detectsAuthException() {
        assertTrue(isMobileAuthFailure(MobileAuthException("缺少或无效的连接 token", 401)))
        assertTrue(isMobileAuthFailure(IllegalStateException(MobileAuthException("设备待主机确认", 403))))
        assertTrue(isMobileAuthFailure(IllegalStateException("设备已被吊销")))
        // 中继（DLP/1）转来的拒绝码不是插件的答复，不算认证失败（RFC §7.4）
        assertFalse(isMobileAuthFailure(dev.deeplinks.core.remote.RouteRejectedException("UNKNOWN_KEY")))
        assertFalse(isMobileAuthFailure(java.io.IOException("AGENT_OFFLINE agent offline")))
        assertFalse(isMobileAuthFailure(SocketTimeoutException("timeout")))
        assertFalse(isMobileAuthFailure(IllegalStateException("刷新会话失败")))
    }

    @Test
    fun userMessageForPendingVsExpired() {
        assertEquals(
            DshStringsZh.devicePendingApproval,
            mobileAuthUserMessage(MobileAuthException("设备待主机确认", 403)),
        )
        assertEquals(
            DshStringsZh.connectionAuthExpired,
            mobileAuthUserMessage(MobileAuthException("缺少或无效的连接 token", 401)),
        )
        assertEquals(
            DshStringsZh.certificateChanged,
            mobileAuthUserMessage(dev.deeplinks.core.PinnedSsl.CertChangedException()),
        )
        assertEquals(
            DshStringsZh.devicePendingApproval,
            mobileAuthUserMessage(MobileAuthException("waiting for approval", 403)),
        )
    }

    @Test
    fun authFailureDoesNotBlockLocalHostRemoval() {
        assertFalse(shouldBlockLocalHostRemoval(null))
        assertFalse(shouldBlockLocalHostRemoval(MobileAuthException("缺少或无效的连接 token", 401)))
        assertTrue(shouldBlockLocalHostRemoval(java.io.IOException("timeout")))
        assertTrue(shouldBlockLocalHostRemoval(java.io.IOException("AGENT_OFFLINE agent offline")))
    }

    @Test
    fun openAuthDropsRevokedPairingButKeepsPendingAndIgnoresRelayCodes() {
        // 中继转来的拒绝码可能伪造：绝不据此删本机配对（RFC §7.4）
        assertFalse(shouldDropLocalHostOnOpenAuth(dev.deeplinks.core.remote.RouteRejectedException("UNKNOWN_KEY")))
        assertTrue(shouldDropLocalHostOnOpenAuth(MobileAuthException("缺少或无效的连接 token", 401)))
        assertTrue(shouldDropLocalHostOnOpenAuth(dev.deeplinks.core.PinnedSsl.CertChangedException()))
        assertFalse(shouldDropLocalHostOnOpenAuth(MobileAuthException("设备待主机确认", 403)))
        assertFalse(shouldDropLocalHostOnOpenAuth(MobileAuthException("waiting for approval", 403)))
        assertFalse(shouldDropLocalHostOnOpenAuth(java.io.IOException("AGENT_OFFLINE agent offline")))
    }

    @Test
    fun remoteConnectErrorsAreUserFacing() {
        // RFC §7.5 的状态文案；包在 OkHttp 的外层异常里也要认得出来
        fun text(e: Throwable) = friendlyNetworkError(java.io.IOException("call failed", e))
        assertEquals(DshStringsZh.remoteRelayUnreachable, text(dev.deeplinks.core.remote.RouteUnreachableException(java.net.UnknownHostException("x"))))
        assertEquals(DshStringsZh.remoteHostOffline, text(dev.deeplinks.core.remote.RouteOfflineException()))
        assertEquals(DshStringsZh.remoteCredentialInvalid, text(dev.deeplinks.core.remote.RouteRejectedException("UNKNOWN_KEY")))
        assertEquals(DshStringsZh.remoteCredentialInvalid, text(dev.deeplinks.core.remote.RouteRejectedException("BAD_MAC")))
        assertEquals(DshStringsZh.remoteClockSkew, text(dev.deeplinks.core.remote.RouteRejectedException("CLOCK_SKEW", 1)))
        assertEquals(DshStringsZh.remoteLocalUnavailable, text(dev.deeplinks.core.remote.RouteRejectedException("LOCAL_UNAVAILABLE")))
        assertEquals(DshStringsZh.remoteQrExpired, text(dev.deeplinks.core.remote.RouteRejectedException("BOOTSTRAP_USED")))
        assertEquals(
            DshStringsZh.requestTooFrequent,
            friendlyNetworkError(java.io.IOException("rate limited")),
        )
    }
}
