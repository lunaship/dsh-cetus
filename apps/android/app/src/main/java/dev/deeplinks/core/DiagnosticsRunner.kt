package dev.deeplinks.core

/**
 * 连接诊断的纯逻辑（P1.2）。网络、探测和拉报告都由调用方注入，JVM 单测不碰 Android。
 *
 * 证书不符只体现在结果里。这里没有删除或吊销入口。
 */
class DiagnosticsRunner(
    private val network: () -> NetworkKind,
    private val lanUrls: () -> List<String>,
    private val probeLan: (url: String) -> LanHit,
    private val hasRemote: Boolean,
    private val probeRemote: () -> RemoteHit,
    private val fetchDiagnostics: () -> DiagnosticsFetch,
    private val clockOffsetSec: () -> Long,
) {
    fun run(): DiagnosticsReport {
        val steps = mutableListOf<DiagStep>()
        val kind = network()
        steps += networkStep(kind)

        val urls = lanUrls()
        val hits = urls.map { url -> probeLan(url) }
        val lanOk = hits.any { it.ok }
        steps += lanStep(hits)

        val remote = if (hasRemote) probeRemote() else RemoteHit(RemoteProbeStatus.UNCONFIGURED)
        steps += remoteStep(remote, lanOk)

        val fetch = fetchDiagnostics()
        val certBad = hits.any { it.failure == LanProbeFailure.CERT_MISMATCH } ||
            remote.status == RemoteProbeStatus.CERT_MISMATCH ||
            fetch is DiagnosticsFetch.CertMismatch
        val certChecked = lanOk ||
            remote.status == RemoteProbeStatus.UP ||
            fetch is DiagnosticsFetch.Ok ||
            fetch is DiagnosticsFetch.Unauthorized ||
            fetch is DiagnosticsFetch.NotFound ||
            fetch is DiagnosticsFetch.OtherHttp
        steps += certStep(certBad, certChecked)

        steps += authStep(fetch, certBad)
        steps += clockStep(clockOffsetSec())

        val hostChecks = if (fetch is DiagnosticsFetch.Ok) {
            fetch.checks.filter { check ->
                diagnosticsTokenOk(check.id) &&
                    diagnosticsTokenOk(check.code) &&
                    check.status in HOST_STATUSES
            }
        } else {
            emptyList()
        }
        return DiagnosticsReport(steps, hostChecks)
    }

    private fun networkStep(kind: NetworkKind): DiagStep = when (kind) {
        NetworkKind.WIFI -> DiagStep("network", DiagStatus.OK, "NET_WIFI", detailKey = "diagNetWifi")
        NetworkKind.CELLULAR -> DiagStep(
            "network",
            DiagStatus.WARN,
            "NET_CELLULAR",
            detailKey = "diagNetCellular",
            suggestionKey = "diagSuggestWifi",
        )
        NetworkKind.NONE -> DiagStep(
            "network",
            DiagStatus.FAIL,
            "NET_NONE",
            detailKey = "diagNetNone",
            suggestionKey = "diagSuggestWifi",
        )
        NetworkKind.UNKNOWN -> DiagStep(
            "network",
            DiagStatus.WARN,
            "NET_UNKNOWN",
            detailKey = "diagNetUnknown",
        )
    }

    private fun lanStep(hits: List<LanHit>): DiagStep {
        if (hits.isEmpty()) {
            return DiagStep(
                "lan",
                DiagStatus.FAIL,
                "LAN_NONE",
                numbers = mapOf("n" to 0L),
                suggestionKey = "diagSuggestLan",
            )
        }
        val okCount = hits.count { it.ok }.toLong()
        val numbers = mapOf(
            "n" to hits.size.toLong(),
            "ok" to okCount,
            "timeout" to hits.count { it.failure == LanProbeFailure.TIMEOUT }.toLong(),
            "refused" to hits.count { it.failure == LanProbeFailure.REFUSED }.toLong(),
            "cert" to hits.count { it.failure == LanProbeFailure.CERT_MISMATCH }.toLong(),
        )
        val best = hits.filter { it.ok }.minOfOrNull { it.elapsedMs }
        if (okCount > 0) {
            return DiagStep("lan", DiagStatus.OK, "LAN_OK", elapsedMs = best, numbers = numbers)
        }
        val failure = when {
            hits.all { it.failure == LanProbeFailure.CERT_MISMATCH } -> LanProbeFailure.CERT_MISMATCH
            hits.all { it.failure == LanProbeFailure.TIMEOUT } -> LanProbeFailure.TIMEOUT
            hits.all { it.failure == LanProbeFailure.REFUSED } -> LanProbeFailure.REFUSED
            else -> LanProbeFailure.UNREACHABLE
        }
        val code = when (failure) {
            LanProbeFailure.CERT_MISMATCH -> "LAN_CERT"
            LanProbeFailure.TIMEOUT -> "LAN_TIMEOUT"
            LanProbeFailure.REFUSED -> "LAN_REFUSED"
            LanProbeFailure.UNREACHABLE -> "LAN_UNREACHABLE"
        }
        return DiagStep(
            "lan",
            DiagStatus.FAIL,
            code,
            elapsedMs = hits.maxOfOrNull { it.elapsedMs },
            numbers = numbers,
            suggestionKey = if (failure == LanProbeFailure.CERT_MISMATCH) null else "diagSuggestLan",
        )
    }

    private fun remoteStep(remote: RemoteHit, lanOk: Boolean): DiagStep = when (remote.status) {
        RemoteProbeStatus.UNCONFIGURED -> DiagStep(
            "remote",
            if (lanOk) DiagStatus.SKIP else DiagStatus.WARN,
            "REMOTE_UNCONFIGURED",
            suggestionKey = if (lanOk) null else "diagSuggestRemoteOff",
        )
        RemoteProbeStatus.UP -> DiagStep("remote", DiagStatus.OK, "REMOTE_UP")
        RemoteProbeStatus.DOWN -> DiagStep(
            "remote",
            DiagStatus.FAIL,
            "REMOTE_DOWN",
            suggestionKey = "diagSuggestRemoteDown",
        )
        RemoteProbeStatus.CERT_MISMATCH -> DiagStep("remote", DiagStatus.FAIL, "REMOTE_CERT")
    }

    private fun certStep(certBad: Boolean, certChecked: Boolean): DiagStep = when {
        certBad -> DiagStep(
            "cert",
            DiagStatus.FAIL,
            "CERT_CHANGED",
            suggestionKey = "diagSuggestCert",
        )
        certChecked -> DiagStep("cert", DiagStatus.OK, "CERT_OK")
        else -> DiagStep("cert", DiagStatus.SKIP, "CERT_UNCHECKED")
    }

    private fun authStep(fetch: DiagnosticsFetch, certBad: Boolean): DiagStep = when (fetch) {
        is DiagnosticsFetch.Ok -> DiagStep("auth", DiagStatus.OK, "AUTH_OK")
        DiagnosticsFetch.Unauthorized -> DiagStep(
            "auth",
            DiagStatus.FAIL,
            "AUTH_REVOKED",
            suggestionKey = "diagSuggestAuth",
        )
        DiagnosticsFetch.NotFound -> DiagStep(
            "auth",
            DiagStatus.SKIP,
            "AUTH_OLD_PLUGIN",
            detailKey = "diagOldPlugin",
        )
        DiagnosticsFetch.CertMismatch -> DiagStep("auth", DiagStatus.SKIP, "AUTH_UNCHECKED")
        DiagnosticsFetch.Unreachable -> DiagStep(
            "auth",
            if (certBad) DiagStatus.SKIP else DiagStatus.FAIL,
            if (certBad) "AUTH_UNCHECKED" else "AUTH_UNREACHABLE",
            suggestionKey = if (certBad) null else "diagSuggestLan",
        )
        is DiagnosticsFetch.OtherHttp -> DiagStep(
            "auth",
            DiagStatus.FAIL,
            "AUTH_HTTP",
            numbers = mapOf("http" to fetch.httpCode.toLong()),
            suggestionKey = "diagSuggestLan",
        )
    }

    private fun clockStep(offsetSec: Long): DiagStep {
        val abs = kotlin.math.abs(offsetSec)
        return if (abs > DIAG_CLOCK_WARN_SEC) {
            DiagStep(
                "clock",
                DiagStatus.WARN,
                "CLOCK_SKEW",
                numbers = mapOf("offsetSec" to abs),
                suggestionKey = "diagSuggestClock",
            )
        } else {
            DiagStep(
                "clock",
                DiagStatus.OK,
                "CLOCK_OK",
                numbers = mapOf("offsetSec" to abs),
            )
        }
    }
}

enum class DiagStatus { OK, WARN, FAIL, SKIP }

data class DiagStep(
    val id: String,
    val status: DiagStatus,
    val code: String,
    val elapsedMs: Long? = null,
    val numbers: Map<String, Long> = emptyMap(),
    val detailKey: String? = null,
    val suggestionKey: String? = null,
)

data class LanHit(
    val ok: Boolean,
    val elapsedMs: Long,
    val failure: LanProbeFailure? = null,
)

enum class RemoteProbeStatus { UNCONFIGURED, UP, DOWN, CERT_MISMATCH }

data class RemoteHit(
    val status: RemoteProbeStatus,
    val httpCode: Int? = null,
)

sealed class DiagnosticsFetch {
    data class Ok(val checks: List<HostCheckView>) : DiagnosticsFetch()
    data object Unauthorized : DiagnosticsFetch()
    data object NotFound : DiagnosticsFetch()
    data class OtherHttp(val httpCode: Int) : DiagnosticsFetch()
    data object CertMismatch : DiagnosticsFetch()
    data object Unreachable : DiagnosticsFetch()
}

data class HostCheckView(
    val id: String,
    val status: String,
    val code: String,
    val numbers: Map<String, Long> = emptyMap(),
    val flags: Map<String, Boolean> = emptyMap(),
)

data class DiagnosticsReport(
    val steps: List<DiagStep>,
    val hostChecks: List<HostCheckView>,
) {
    fun copyText(): String = diagnosticsCopyText(this)
}

/** 超过这个秒数，时钟检查记为 warn。等于 120 仍是 ok。 */
const val DIAG_CLOCK_WARN_SEC = 120L

/** 设置页灰点：从未连上，或上次成功早于这个间隔。方案没有写死天数，这里取 7 天。 */
const val HOST_LIST_STALE_MS = 7L * 24 * 60 * 60 * 1000

enum class HostListTone { GREEN, YELLOW, RED, GRAY }

/**
 * 设置里主机列表的状态点。只看已有快照，不探测。
 *
 * [online] 为 null 表示这一轮还没探到：从未记录或已超过 [HOST_LIST_STALE_MS] 为灰，
 * 最近有成功记录则为绿（[LastOnlineStore] 只在成功时写入，没有远程/直连之分）。
 */
fun hostListTone(
    online: Boolean?,
    viaRemote: Boolean,
    lastOnlineAtMs: Long,
    nowMs: Long,
    hasWarn: Boolean = false,
): HostListTone {
    if (online == true) {
        return if (viaRemote || hasWarn) HostListTone.YELLOW else HostListTone.GREEN
    }
    if (online == false) return HostListTone.RED
    if (lastOnlineAtMs <= 0L) return HostListTone.GRAY
    if (nowMs - lastOnlineAtMs >= HOST_LIST_STALE_MS) return HostListTone.GRAY
    return HostListTone.GREEN
}

private val SAFE_TOKEN = Regex("^[A-Za-z0-9_.-]{1,32}$")
private val LONG_HEX = Regex("^[0-9a-fA-F]{16,}$")
private val IPV4 = Regex("\\d{1,3}(?:\\.\\d{1,3}){3}")

internal fun diagnosticsTokenOk(value: String): Boolean {
    if (!SAFE_TOKEN.matches(value) || LONG_HEX.matches(value) || IPV4.containsMatchIn(value)) return false
    val lower = value.lowercase()
    return !lower.contains("token") &&
        !lower.contains("secret") &&
        !lower.contains("password") &&
        !lower.contains("bearer")
}

/** 剪贴板文本：枚举和数字。地址、凭据、路径整行丢掉。 */
internal fun diagnosticsCopyText(report: DiagnosticsReport): String {
    val lines = mutableListOf("v=1")
    for (step in report.steps) {
        val id = step.id.takeIf { diagnosticsTokenOk(it) } ?: continue
        val code = step.code.takeIf { diagnosticsTokenOk(it) } ?: "DROPPED"
        val status = step.status.name.lowercase()
        val extras = StringBuilder()
        step.elapsedMs?.takeIf { it in 0L..999_999L }?.let { extras.append(" ms=").append(it) }
        for ((key, value) in step.numbers) {
            if (!diagnosticsTokenOk(key)) continue
            extras.append(' ').append(key).append('=').append(value)
        }
        lines += "$id $status $code$extras"
    }
    for (check in report.hostChecks) {
        if (!diagnosticsTokenOk(check.id) || !diagnosticsTokenOk(check.code)) continue
        if (check.status !in HOST_STATUSES) continue
        val extras = StringBuilder()
        for ((key, value) in check.numbers) {
            if (!diagnosticsTokenOk(key)) continue
            extras.append(' ').append(key).append('=').append(value)
        }
        for ((key, value) in check.flags) {
            if (!diagnosticsTokenOk(key)) continue
            extras.append(' ').append(key).append('=').append(value)
        }
        lines += "host ${check.id} ${check.status} ${check.code}$extras"
    }
    return lines.joinToString("\n")
}

private val HOST_STATUSES = setOf("ok", "warn", "fail", "skip")
