package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppSettingsCacheKeyTest {

    @Test
    fun `renaming a host keeps the same cache id`() {
        val original = Host("书房", "HTTP://Host.Example:18640/", "token", "device-1", "AA:BB")
        val renamed = original.copy(name = "办公室")
        assertEquals(appSettingsHostCacheId(original), appSettingsHostCacheId(renamed))
    }

    @Test
    fun `different host identity fields do not share cache id`() {
        val base = Host("a", "https://host.example:18640", "token", "device-1", "aa")
        assertNotEquals(appSettingsHostCacheId(base), appSettingsHostCacheId(base.copy(baseUrl = "https://other.example:18640")))
        assertNotEquals(appSettingsHostCacheId(base), appSettingsHostCacheId(base.copy(certFingerprint = "bb")))
        assertNotEquals(appSettingsHostCacheId(base), appSettingsHostCacheId(base.copy(deviceId = "device-2")))
    }

    @Test
    fun `remote capability does not move the settings namespace`() {
        val lan = Host("a", "https://host.example:18640", "token", "device-1", "aa")
        val remote = lan.copy(remoteEndpoint = "wss://relay.example/ws", remoteRouteId = "r", remoteHandle = "h", remoteKey = "k")
        assertEquals(appSettingsHostCacheId(lan), appSettingsHostCacheId(remote))
    }

    @Test
    fun `LAN host id is unchanged from the pre-DLP formula`() {
        // 旧公式里 DLR/1 的 relayClient / relayRouteId 对局域网电脑本来就是空串：去掉字段后 id 不能变
        val host = Host("a", "https://host.example:18640", "token", "device-1", "AA:BB")
        val legacy = listOf(
            PinnedSsl.normalizeUrl(host.baseUrl).trimEnd('/').lowercase(),
            PinnedSsl.normalizeFingerprint(host.certFingerprint),
            host.deviceId.trim(),
            "",
            "",
        ).joinToString("\u001f")
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(legacy.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
        assertEquals(expected, appSettingsHostCacheId(host))
    }
}
