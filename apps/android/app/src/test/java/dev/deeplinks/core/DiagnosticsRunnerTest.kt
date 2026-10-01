package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException

class DiagnosticsRunnerTest {

    @Test
    fun `wifi lan and remote all pass`() {
        val report = runner(
            kind = NetworkKind.WIFI,
            lan = listOf(LanHit(true, 18)),
            remote = RemoteHit(RemoteProbeStatus.UP),
            fetch = DiagnosticsFetch.Ok(
                listOf(HostCheckView("host.rpc", "ok", "HOST_RPC_OK", mapOf("ms" to 12))),
            ),
            offsetSec = 4,
        ).run()
        assertEquals(DiagStatus.OK, step(report, "network").status)
        assertEquals("LAN_OK", step(report, "lan").code)
        assertEquals(DiagStatus.OK, step(report, "remote").status)
        assertEquals("CERT_OK", step(report, "cert").code)
        assertEquals("AUTH_OK", step(report, "auth").code)
        assertEquals(DiagStatus.OK, step(report, "clock").status)
        assertEquals("HOST_RPC_OK", report.hostChecks.single().code)
        assertTrue(report.copyText().contains("ms=12"))
    }

    @Test
    fun `cellular suggests another path and does not claim the lan is up`() {
        val report = runner(
            kind = NetworkKind.CELLULAR,
            lan = listOf(LanHit(false, 800, LanProbeFailure.TIMEOUT)),
            remote = RemoteHit(RemoteProbeStatus.UP),
            fetch = DiagnosticsFetch.Ok(emptyList()),
        ).run()
        assertEquals("NET_CELLULAR", step(report, "network").code)
        assertEquals("diagSuggestWifi", step(report, "network").suggestionKey)
        assertEquals("LAN_TIMEOUT", step(report, "lan").code)
        assertEquals("diagSuggestLan", step(report, "lan").suggestionKey)
        assertEquals(DiagStatus.OK, step(report, "remote").status)
    }

    @Test
    fun `no network is a failure`() {
        val report = runner(kind = NetworkKind.NONE, lan = emptyList()).run()
        assertEquals(DiagStatus.FAIL, step(report, "network").status)
        assertEquals("LAN_NONE", step(report, "lan").code)
    }

    @Test
    fun `cert mismatch is explicit and does not invent a revoked credential`() {
        val report = runner(
            lan = listOf(LanHit(false, 40, LanProbeFailure.CERT_MISMATCH)),
            remote = RemoteHit(RemoteProbeStatus.CERT_MISMATCH),
            fetch = DiagnosticsFetch.CertMismatch,
        ).run()
        assertEquals("CERT_CHANGED", step(report, "cert").code)
        assertEquals("diagSuggestCert", step(report, "cert").suggestionKey)
        assertEquals(DiagStatus.SKIP, step(report, "auth").status)
        assertEquals("AUTH_UNCHECKED", step(report, "auth").code)
        assertTrue(report.hostChecks.isEmpty())
    }

    @Test
    fun `unauthorized fetch is revoked and does not look like a dead plugin`() {
        val report = runner(fetch = DiagnosticsFetch.Unauthorized).run()
        assertEquals("AUTH_REVOKED", step(report, "auth").code)
        assertEquals(DiagStatus.FAIL, step(report, "auth").status)
        assertEquals("diagSuggestAuth", step(report, "auth").suggestionKey)
        assertTrue(report.hostChecks.isEmpty())
    }

    @Test
    fun `missing diagnostics route is an old plugin, not a revoked credential`() {
        val report = runner(
            lan = listOf(LanHit(true, 11)),
            fetch = DiagnosticsFetch.NotFound,
        ).run()
        assertEquals("AUTH_OLD_PLUGIN", step(report, "auth").code)
        assertEquals(DiagStatus.SKIP, step(report, "auth").status)
        assertEquals("CERT_OK", step(report, "cert").code)
        assertTrue(report.hostChecks.isEmpty())
        assertEquals(DiagStatus.OK, step(report, "lan").status)
    }

    @Test
    fun `clock warns only past 120 seconds`() {
        val exact = runner(offsetSec = 120).run()
        val over = runner(offsetSec = -121).run()
        assertEquals(DiagStatus.OK, step(exact, "clock").status)
        assertEquals(120L, exact.steps.first { it.id == "clock" }.numbers["offsetSec"])
        assertEquals(DiagStatus.WARN, step(over, "clock").status)
        assertEquals(121L, over.steps.first { it.id == "clock" }.numbers["offsetSec"])
        assertEquals("diagSuggestClock", step(over, "clock").suggestionKey)
    }

    @Test
    fun `remote is skipped when lan works and no remote is configured`() {
        val report = runner(
            lan = listOf(LanHit(true, 9)),
            hasRemote = false,
        ).run()
        assertEquals(DiagStatus.SKIP, step(report, "remote").status)
        assertEquals("REMOTE_UNCONFIGURED", step(report, "remote").code)
    }

    @Test
    fun `remote down is its own failure`() {
        val report = runner(
            lan = listOf(LanHit(false, 800, LanProbeFailure.REFUSED)),
            remote = RemoteHit(RemoteProbeStatus.DOWN),
            fetch = DiagnosticsFetch.Unreachable,
        ).run()
        assertEquals("REMOTE_DOWN", step(report, "remote").code)
        assertEquals("diagSuggestRemoteDown", step(report, "remote").suggestionKey)
        assertEquals("AUTH_UNREACHABLE", step(report, "auth").code)
    }

    @Test
    fun `second lan address can succeed after the first fails`() {
        val seen = mutableListOf<String>()
        val report = DiagnosticsRunner(
            network = { NetworkKind.WIFI },
            lanUrls = { listOf("https://10.0.0.2/", "https://100.64.0.2/") },
            probeLan = { url ->
                seen += url
                if (url.contains("100.64")) LanHit(true, 30) else LanHit(false, 800, LanProbeFailure.TIMEOUT)
            },
            hasRemote = true,
            probeRemote = { RemoteHit(RemoteProbeStatus.UP) },
            fetchDiagnostics = { DiagnosticsFetch.Ok(emptyList()) },
            clockOffsetSec = { 0 },
        ).run()
        assertEquals(listOf("https://10.0.0.2/", "https://100.64.0.2/"), seen)
        assertEquals("LAN_OK", step(report, "lan").code)
        assertEquals(1L, step(report, "lan").numbers["ok"])
        assertEquals(2L, step(report, "lan").numbers["n"])
        assertFalse(report.copyText().contains("10.0.0.2"))
        assertFalse(report.copyText().contains("100.64"))
    }

    @Test
    fun `copy text keeps enums and numbers and drops addresses and secrets`() {
        val secretUrl = "https://10.9.8.7/Users/secret/token-SECRETVALUE"
        val report = DiagnosticsRunner(
            network = { NetworkKind.WIFI },
            lanUrls = { listOf(secretUrl) },
            probeLan = { LanHit(true, 15) },
            hasRemote = false,
            probeRemote = { error("probe should not run") },
            fetchDiagnostics = {
                DiagnosticsFetch.Ok(
                    listOf(
                        HostCheckView("host.rpc", "ok", "HOST_RPC_OK", mapOf("ms" to 4)),
                        HostCheckView("../etc/passwd", "ok", "HOST_RPC_OK"),
                        HostCheckView("tls.cert", "ok", "token-leak"),
                        HostCheckView("pairing.devices", "ok", "PAIRING_OK", flags = mapOf("valid" to true)),
                    ),
                )
            },
            clockOffsetSec = { 0 },
        ).run()
        val text = report.copyText()
        assertFalse(text.contains("10.9.8.7"))
        assertFalse(text.contains("SECRETVALUE"))
        assertFalse(text.contains("passwd"))
        assertFalse(text.contains("token"))
        assertFalse(text.contains("/"))
        assertTrue(text.contains("host.rpc ok HOST_RPC_OK ms=4"))
        assertTrue(text.contains("valid=true"))
        assertEquals(listOf("host.rpc", "pairing.devices"), report.hostChecks.map { it.id })
        assertFalse(report.toString().contains("SECRETVALUE"))
        assertFalse(report.toString().contains("passwd"))
    }

    @Test
    fun `parser drops paths and keeps numeric detail`() {
        val checks = parseDiagnosticsChecks(
            """
            {"checks":[
              {"id":"host.rpc","status":"ok","code":"HOST_RPC_OK","detail":{"ms":12,"path":"/Users/secret"}},
              {"id":"pairing.devices","status":"ok","code":"PAIRING_OK","detail":{"valid":true}},
              {"id":"tls.cert","status":"warn","code":"TLS_CERT_EXPIRING","detail":{"fp":"abcdef1234567890"}},
              {"id":"bad id","status":"ok","code":"X"}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("host.rpc", "pairing.devices", "tls.cert"), checks.map { it.id })
        assertEquals(12L, checks[0].numbers["ms"])
        assertFalse(checks[0].numbers.containsKey("path"))
        assertEquals(true, checks[1].flags["valid"])
        assertTrue(checks[2].numbers.isEmpty())
    }

    @Test
    fun `probe failures classify without keeping the message`() {
        assertEquals(LanProbeFailure.TIMEOUT, classifyLanProbeFailure(SocketTimeoutException("connect timed out 10.1.2.3")))
        assertEquals(LanProbeFailure.REFUSED, classifyLanProbeFailure(ConnectException("Connection refused")))
        assertEquals(
            LanProbeFailure.CERT_MISMATCH,
            classifyLanProbeFailure(java.io.IOException(PinnedSsl.CertChangedException())),
        )
        assertEquals(LanProbeFailure.UNREACHABLE, classifyLanProbeFailure(java.io.IOException("handshake")))
    }

    @Test
    fun `network kind matches the existing lanCapable split`() {
        assertEquals(NetworkKind.NONE, networkKindFrom(false, false, false, false, false))
        assertEquals(NetworkKind.UNKNOWN, networkKindFrom(true, false, false, false, false))
        assertEquals(NetworkKind.UNKNOWN, networkKindFrom(true, true, false, false, true))
        assertEquals(NetworkKind.WIFI, networkKindFrom(true, true, true, false, false))
        assertEquals(NetworkKind.WIFI, networkKindFrom(true, true, false, true, false))
        assertEquals(NetworkKind.CELLULAR, networkKindFrom(true, true, false, false, false))
        assertEquals(null, lanCapableFrom(false, false, false, false, false))
        assertEquals(true, lanCapableFrom(true, true, true, false, false))
        assertEquals(null, lanCapableFrom(true, true, false, false, true))
        assertEquals(false, lanCapableFrom(true, true, false, false, false))
    }

    @Test
    fun `host list tone uses the existing snapshot only`() {
        val now = 1_700_000_000_000L
        assertEquals(HostListTone.GREEN, hostListTone(true, false, now, now))
        assertEquals(HostListTone.YELLOW, hostListTone(true, true, now, now))
        assertEquals(HostListTone.YELLOW, hostListTone(true, false, now, now, hasWarn = true))
        assertEquals(HostListTone.RED, hostListTone(false, false, now, now))
        assertEquals(HostListTone.GRAY, hostListTone(null, false, 0L, now))
        assertEquals(HostListTone.GRAY, hostListTone(null, false, now - HOST_LIST_STALE_MS, now))
        assertEquals(HostListTone.GREEN, hostListTone(null, false, now - 60_000L, now))
    }

    @Test
    fun `diagnostics sources do not drop or revoke credentials`() {
        val root = sourceRoot()
        val files = listOf(
            "dev/deeplinks/core/DiagnosticsRunner.kt",
            "dev/deeplinks/core/DiagnosticsProbes.kt",
            "dev/deeplinks/native/ConnectionDiagnostics.kt",
        )
        for (rel in files) {
            val text = File(root, rel).readText()
            assertFalse(rel, text.contains("HostStore.remove"))
            assertFalse(rel, text.contains("revokePairedDevice"))
            assertFalse(rel, text.contains("clearLockAndReplace"))
        }
    }

    private fun step(report: DiagnosticsReport, id: String) = report.steps.first { it.id == id }

    private fun runner(
        kind: NetworkKind = NetworkKind.WIFI,
        lan: List<LanHit> = listOf(LanHit(true, 10)),
        remote: RemoteHit = RemoteHit(RemoteProbeStatus.UP),
        hasRemote: Boolean = true,
        fetch: DiagnosticsFetch = DiagnosticsFetch.Ok(emptyList()),
        offsetSec: Long = 0,
    ) = DiagnosticsRunner(
        network = { kind },
        lanUrls = { if (lan.isEmpty()) emptyList() else List(lan.size) { "https://lan" } },
        probeLan = { lan[0] },
        hasRemote = hasRemote,
        probeRemote = { remote },
        fetchDiagnostics = { fetch },
        clockOffsetSec = { offsetSec },
    )

    private fun sourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        return File(requireNotNull(dir), "src/main/java")
    }
}
