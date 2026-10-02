package dev.deeplinks.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostStoreTest {

    @Test
    fun `json 序列化往返一致`() {
        val hosts = listOf(
            Host("书房", "https://10.0.0.2:18640", "tok-1", "dev-abc", "aabbcc"),
            Host("办公室", "https://dsh.example.com", "tok-2"),
        )
        val json = HostStore.hostsToJson(hosts)
        val parsed = HostStore.hostsFromJson(json)
        assertEquals(hosts, parsed)
    }

    private val route16 = "AAAAAAAAAAAAAAAAAAAAAA"
    private val handle16 = "AQEBAQEBAQEBAQEBAQEBAQ"
    private val key32 = "A".repeat(43)

    private fun remoteHost() = Host(
        name = "书房",
        baseUrl = "https://10.0.0.2:18640",
        token = "tok-1",
        deviceId = "dev-abc",
        certFingerprint = "aabbcc",
        remoteEndpoint = "wss://relay.example/ws",
        remoteRouteId = route16,
        remoteHandle = handle16,
        remoteKey = key32,
    )

    @Test
    fun `remote 字段往返一致`() {
        val host = remoteHost()
        assertEquals(listOf(host), HostStore.hostsFromJson(HostStore.hostsToJson(listOf(host))))
        assertTrue(host.hasRemote)
        assertEquals("wss://relay.example/ws", host.remoteRoute()?.endpoint)
        assertEquals("device", host.remoteRoute()?.kind)
    }

    @Test
    fun `远程不改本机缓存槽位，withoutRemote 只清远程字段`() {
        val host = remoteHost()
        assertEquals("lan|书房|https://10.0.0.2:18640", host.slotKey)
        val lan = host.withoutRemote()
        assertEquals(false, lan.hasRemote)
        assertEquals(host.token, lan.token)
        assertEquals(host.slotKey, lan.slotKey)
    }

    @Test
    fun `旧版 DLR 1 relay 字段读到即丢弃`() {
        val legacy = HostStore.hostsFromJson(
            """[{"name":"书房","baseUrl":"https://10.0.0.2:18640","token":"tok","relayClient":"relay.dshlinks.com:8443","relayRouteId":"rid","relayRouteSecret":"sec","preferRelay":true,"needsCloudRescan":true}]""",
        )
        assertEquals(listOf(Host("书房", "https://10.0.0.2:18640", "tok")), legacy)
        assertTrue(!HostStore.hostsToJson(legacy).contains("relay"))
    }

    @Test
    fun `bootstrap 缺 remote 键时保留已有远程能力`() {
        val host = remoteHost()
        assertEquals(host, applyBootstrapRemote(host, JSONObject("""{"protocol":2,"relay":null}""")))
    }

    @Test
    fun `bootstrap remote null 只清远程字段`() {
        val host = remoteHost()
        val cleared = applyBootstrapRemote(host, JSONObject("""{"remote":null}"""))
        assertEquals(host.withoutRemote(), cleared)
    }

    @Test
    fun `bootstrap remote 对象补齐远程能力`() {
        val lan = Host("书房", "https://10.0.0.2:18640", "tok")
        val root = JSONObject("""{"remote":{"e":"wss://relay.example/ws","r":"$route16","h":"$handle16","k":"$key32","p":"${"c".repeat(64)}"}}""")
        val updated = applyBootstrapRemote(lan, root)
        assertTrue(updated.hasRemote)
        assertEquals(handle16, updated.remoteHandle)
        assertEquals("c".repeat(64), updated.remoteOuterPin)
    }

    @Test
    fun `bootstrap 残缺 remote 对象不改配对`() {
        val host = remoteHost()
        assertEquals(host, applyBootstrapRemote(host, JSONObject("""{"remote":{"e":"wss://x/ws"}}""")))
        // 非 wss 端点同样拒绝，不因一次坏数据丢掉能用的凭据
        val insecure = JSONObject("""{"remote":{"e":"ws://x/ws","r":"$route16","h":"$handle16","k":"$key32"}}""")
        assertEquals(host, applyBootstrapRemote(host, insecure))
    }

    @Test
    fun `旧数据无 deviceId 时兼容为空串`() {
        val legacy = """[{"name":"老设备","baseUrl":"http://1:18640","token":"t"}]"""
        val parsed = HostStore.hostsFromJson(legacy)
        assertEquals("", parsed[0].deviceId)
        assertEquals("老设备", parsed[0].name)
        assertEquals("https://1:18640", parsed[0].baseUrl)
        assertEquals("", parsed[0].certFingerprint)
        assertEquals("", parsed[0].tailnetUrl)
    }

    @Test
    fun `旧数据没有 tailnetUrl 时备用地址为空且槽位不变`() {
        val legacy = """[{"name":"书房","baseUrl":"https://10.0.0.2:18640","token":"tok","deviceId":"dev"}]"""
        val parsed = HostStore.hostsFromJson(legacy)
        assertEquals("", parsed[0].tailnetUrl)
        assertEquals("lan|书房|https://10.0.0.2:18640", parsed[0].slotKey)
        assertEquals(listOf("https://10.0.0.2:18640"), parsed[0].directLanUrls())
        val again = HostStore.hostsFromJson(HostStore.hostsToJson(parsed))
        assertEquals("", again[0].tailnetUrl)
    }

    @Test
    fun `tailnetUrl 往返且与主地址不同时排在后面`() {
        val host = Host(
            "书房",
            "https://10.0.0.2:18640",
            "tok",
            tailnetUrl = "https://100.64.0.8:18640",
        )
        assertEquals(listOf(host), HostStore.hostsFromJson(HostStore.hostsToJson(listOf(host))))
        assertEquals(
            listOf("https://10.0.0.2:18640", "https://100.64.0.8:18640"),
            host.directLanUrls(),
        )
        assertEquals(listOf(host.baseUrl), host.copy(tailnetUrl = host.baseUrl).directLanUrls())
    }

    @Test
    fun `非法 json 返回空列表`() {
        assertTrue(HostStore.hostsFromJson("not json").isEmpty())
        assertTrue(HostStore.hostsFromJson("").isEmpty())
        assertTrue(HostStore.hostsFromJson("[{\"name\":\"x\"}]").isEmpty()) // 缺字段
    }

    @Test
    fun `按名称或地址解析设备`() {
        val mac = Host("书房", "https://10.0.0.2:18640", "tok-a")
        val mini = Host("客厅", "https://10.0.0.3:18640", "tok-b")
        val hosts = listOf(mac, mini)
        assertEquals(mini, hosts.resolveHost("客厅", null))
        assertEquals(mini, hosts.resolveHost(null, mini.baseUrl))
        assertEquals(mac, hosts.resolveHost(null, null))
    }

    @Test
    fun `旧版多台记录收敛为最近使用的那台`() {
        val mac = Host("Mac", "https://10.0.0.2:18640", "t1", deviceId = "dev-mac")
        val mini = Host("mini", "https://10.0.0.3:18640", "t2", deviceId = "dev-mini")
        assertEquals(mini, HostStore.singleHostOf(listOf(mac, mini), lastIdentity = "device:dev-mini"))
    }

    @Test
    fun `无最近记录时取列表首项`() {
        val mac = Host("Mac", "https://10.0.0.2:18640", "t1")
        val mini = Host("mini", "https://10.0.0.3:18640", "t2")
        assertEquals(mac, HostStore.singleHostOf(listOf(mac, mini), lastIdentity = null))
        assertEquals(mac, HostStore.singleHostOf(listOf(mac, mini), lastIdentity = "device:gone"))
    }

    @Test
    fun `空列表没有设备`() {
        assertEquals(null, HostStore.singleHostOf(emptyList(), lastIdentity = "device:dev-mac"))
    }
}
