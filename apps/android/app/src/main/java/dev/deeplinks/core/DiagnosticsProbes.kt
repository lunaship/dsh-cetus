package dev.deeplinks.core

import dev.deeplinks.core.remote.HostRoute
import dev.deeplinks.core.remote.RouteSelector
import org.json.JSONObject

/**
 * 把 [DiagnosticsRunner] 接到现有传输上。
 *
 * 局域网地址列表由调用方传入（P1.2 只有主地址；P1.3 可以加上 Tailscale 备用地址）。
 * 证书不符只返回结果，不删除、不吊销本机凭据。
 */
internal fun diagnosticsRunnerFor(host: Host, lanUrls: List<String>): DiagnosticsRunner {
    var remoteFetch: DiagnosticsFetch? = null
    return DiagnosticsRunner(
        network = { NetworkTransport.snapshotKind },
        lanUrls = { lanUrls },
        probeLan = { url ->
            val detail = HostHttp.probeLanDetail(url, host.certFingerprint)
            LanHit(detail.ok, detail.elapsedMs, detail.failure)
        },
        hasRemote = host.hasRemote,
        probeRemote = {
            if (!host.hasRemote) {
                RemoteHit(RemoteProbeStatus.UNCONFIGURED)
            } else {
                val fetched = fetchDiagnostics(host, HostRoute.REMOTE)
                remoteFetch = fetched
                remoteHitFrom(fetched)
            }
        },
        fetchDiagnostics = {
            val reused = remoteFetch
            if (reused is DiagnosticsFetch.Ok ||
                reused is DiagnosticsFetch.Unauthorized ||
                reused is DiagnosticsFetch.NotFound
            ) {
                reused
            } else {
                fetchDiagnostics(host, forceRoute = null)
            }
        },
        clockOffsetSec = { RouteSelector.shared.clockOffsetSec(HostHttp.routeKey(host)) },
    )
}

private fun remoteHitFrom(fetch: DiagnosticsFetch): RemoteHit = when (fetch) {
    is DiagnosticsFetch.Ok,
    is DiagnosticsFetch.Unauthorized,
    is DiagnosticsFetch.NotFound,
    is DiagnosticsFetch.OtherHttp,
    -> RemoteHit(RemoteProbeStatus.UP)
    DiagnosticsFetch.CertMismatch -> RemoteHit(RemoteProbeStatus.CERT_MISMATCH)
    DiagnosticsFetch.Unreachable -> RemoteHit(RemoteProbeStatus.DOWN)
}

internal fun fetchDiagnostics(host: Host, forceRoute: HostRoute?): DiagnosticsFetch {
    if (host.token.isBlank()) return DiagnosticsFetch.Unauthorized
    return try {
        HostHttp.execute(
            host,
            HostHttp.DshRequest(
                method = "GET",
                path = DIAGNOSTICS_PATH,
                headers = listOf(
                    "x-dsh-link-token" to host.token,
                    "Authorization" to "Bearer ${host.token}",
                    "Accept" to "application/json",
                    "Accept-Encoding" to "identity",
                ),
            ),
            forceRoute = forceRoute,
        ).use { response ->
            val code = response.code
            val text = response.body.string().take(MAX_BODY)
            when (code) {
                in 200..299 -> DiagnosticsFetch.Ok(parseDiagnosticsChecks(text))
                401, 403 -> DiagnosticsFetch.Unauthorized
                404 -> DiagnosticsFetch.NotFound
                else -> DiagnosticsFetch.OtherHttp(code)
            }
        }
    } catch (e: Exception) {
        if (PinnedSsl.unwrap(e) is PinnedSsl.CertChangedException) {
            DiagnosticsFetch.CertMismatch
        } else {
            DiagnosticsFetch.Unreachable
        }
    }
}

/**
 * 只留下检查 id / 状态 / code，以及 detail 里的数字和布尔。
 * 字符串 detail 必须是短枚举；路径和长十六进制丢掉。
 */
internal fun parseDiagnosticsChecks(json: String): List<HostCheckView> {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
    val arr = root.optJSONArray("checks") ?: return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val id = row.optString("id")
            val status = row.optString("status")
            val code = row.optString("code")
            if (!diagnosticsTokenOk(id) || !diagnosticsTokenOk(code)) continue
            if (status !in setOf("ok", "warn", "fail", "skip")) continue
            val numbers = linkedMapOf<String, Long>()
            val flags = linkedMapOf<String, Boolean>()
            val detail = row.optJSONObject("detail")
            if (detail != null) {
                val keys = detail.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!diagnosticsTokenOk(key)) continue
                    when (val value = detail.opt(key)) {
                        is Boolean -> flags[key] = value
                        is Number -> {
                            val asLong = value.toLong()
                            if (asLong in -1_000_000_000L..10_000_000_000L) numbers[key] = asLong
                        }
                    }
                }
            }
            add(HostCheckView(id, status, code, numbers, flags))
        }
    }
}

private const val DIAGNOSTICS_PATH = "/dsh-link/mobile/diagnostics"
private const val MAX_BODY = 64 * 1024
