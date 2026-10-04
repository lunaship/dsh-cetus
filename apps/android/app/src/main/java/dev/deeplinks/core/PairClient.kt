package dev.deeplinks.core

import dev.deeplinks.core.remote.HostRoute
import dev.deeplinks.core.remote.RemoteRoute
import dev.deeplinks.core.remote.RouteRejectedException
import dev.deeplinks.devices.PairingQr
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

sealed class HostHealth {
    data class Ok(val latencyMs: Long) : HostHealth()
    data object Unreachable : HostHealth()
    data class AuthFailed(val error: Throwable) : HostHealth()
}

internal fun classifyHostHealthError(error: Throwable): HostHealth {
    val unwrapped = if (error is Exception) PinnedSsl.unwrap(error) else error
    if (unwrapped is PinnedSsl.CertChangedException) return HostHealth.AuthFailed(unwrapped)
    // 中继转来的拒绝码（UNKNOWN_KEY 等）不可信，只算「连不上」，绝不当成凭据失效（RFC §7.4）
    return HostHealth.Unreachable
}

private const val LAN_PROBE_WAIT_MS = 1_500L

/** `100.64.0.0/10` 与 Tailscale IPv6 `fd7a:115c:a1e0::/48`。二维码没有分类字段，按地址本身认。 */
internal fun isTailnetUrl(url: String): Boolean {
    val host = runCatching { java.net.URI(PinnedSsl.normalizeUrl(url)).host }.getOrNull() ?: return false
    val raw = host.trim().lowercase().removePrefix("[").removeSuffix("]")
    if (raw.startsWith("fd7a:115c:a1e0:")) return true
    val parts = raw.split('.')
    if (parts.size != 4) return false
    val a = parts[0].toIntOrNull() ?: return false
    val b = parts[1].toIntOrNull() ?: return false
    if (parts[2].toIntOrNull() == null || parts[3].toIntOrNull() == null) return false
    return a == 100 && b in 64..127
}

object PairClient {
    data class Result(
        val baseUrl: String,
        val name: String,
        val token: String,
        val deviceId: String = "",
        val certFingerprint: String = "",
        val pending: Boolean = false,
        /** 插件已启用远程时下发的设备远程能力；旧插件或未启用时为 null。 */
        val remote: RemoteRoute? = null,
        /** 二维码里与主地址不同的 Tailscale 地址。主地址本身已是 tailnet 时为空。 */
        val tailnetUrl: String = "",
        /** 本次配对实际使用的路由（由成功的 pairOn 指定）。 */
        val pairRoute: HostRoute = HostRoute.LAN,
        /** 服务器返回的 pending 过期时间（Unix 毫秒）；非 pending 时为 null。 */
        val pendingExpiresAt: Long? = null,
        /** 服务器当前时间（Unix 毫秒），可选；null 表示旧插件未返回。 */
        val serverNow: Long? = null,
    )

    fun normalize(baseUrl: String): String = PinnedSsl.normalizeUrl(baseUrl)

    /** 手动输入地址的局域网配对（没有二维码，也就没有远程首配能力）。 */
    fun pair(
        baseUrl: String,
        code: String,
        deviceName: String,
        certFingerprint: String? = null,
        requestId: String = UUID.randomUUID().toString(),
    ): Result = pairOn(normalize(baseUrl), certFingerprint?.takeIf { it.isNotBlank() }, code, deviceName, requestId, HostRoute.LAN, null)

    /**
     * 扫码配对（RFC §7.6）：同一张码既能局域网首配，也能远程首配。
     *
     * 1. 并行探测码里的局域网地址（TCP + 钉扎 TLS，≤1.2 秒）；有通的就在那里配对；
     * 2. 局域网全不通、或局域网配对在建立期失败，且码里有 remote → 经中继以 bootstrap 身份配对，
     *    沿用同一个 requestId（插件按它去重，请求可能已经送达过也不会配出两台）；
     * 3. 配对码错误、过期、证书不符是认证失败，不换路径重试。
     */
    internal fun pairWithQr(
        qr: PairingQr,
        deviceName: String,
        requestId: String = UUID.randomUUID().toString(),
        lanCapable: Boolean? = NetworkTransport.lanCapable,
        probe: (String, String) -> Boolean = HostHttp::probeLanUrl,
        send: (String, String?, String, String, String, HostRoute, RemoteRoute?) -> Result = ::pairOn,
    ): Result {
        val pin = qr.certFingerprint.takeIf { it.isNotBlank() }
        val remote = qr.remote
        if (remote == null || pin == null) {
            for (url in qr.urls) {
                try {
                    return withTailnetSpare(send(normalize(url), pin, qr.code, deviceName, requestId, HostRoute.LAN, null), qr.urls)
                } catch (e: Exception) {
                    if (!canFallBackToRemote(e)) throw e
                }
            }
            val failure = when (qr.remoteCapability) {
                dev.deeplinks.devices.QrRemoteCapability.INVALID, dev.deeplinks.devices.QrRemoteCapability.LEGACY ->
                    PairFailure.badQr(L.pairRemoteInvalid)
                else -> PairFailure(PairFailureCode.QR_NO_REMOTE, PairRecovery.RESCAN, L.pairNoRemote)
            }
            throw PairRequestException(failure)
        }
        val reachable = if (lanCapable == false) null else firstReachable(qr.urls.map(::normalize), LAN_PROBE_WAIT_MS) { probe(it, pin) }
        if (reachable != null) {
            try {
                return withTailnetSpare(send(reachable, pin, qr.code, deviceName, requestId, HostRoute.LAN, null), qr.urls)
            } catch (e: Exception) {
                if (!canFallBackToRemote(e)) throw e
            }
        }
        // 远程首配时 baseUrl 只用来拼请求路径，隧道不连它。主地址仍是码里的原地址。
        val base = qr.urls.firstOrNull()?.let(::normalize) ?: "https://127.0.0.1:18640"
        return withTailnetSpare(send(base, pin, qr.code, deviceName, requestId, HostRoute.REMOTE, remote), qr.urls)
    }

    /** 主地址照旧。码里另有一条不同的 Tailscale 地址时，存成备用直连。 */
    internal fun tailnetSpare(urls: List<String>, primary: String): String {
        val primaryNorm = normalize(primary).trimEnd('/').lowercase()
        val tail = urls.map(::normalize).firstOrNull { isTailnetUrl(it) } ?: return ""
        if (tail.trimEnd('/').lowercase() == primaryNorm) return ""
        return tail
    }

    private fun withTailnetSpare(result: Result, urls: List<String>): Result {
        val spare = tailnetSpare(urls, result.baseUrl)
        return if (spare.isEmpty()) result else result.copy(tailnetUrl = spare)
    }

    /** 只有建立期失败才换到远程；证书不符与插件的明确答复（码错、同名）都不换。 */
    internal fun canFallBackToRemote(error: Throwable): Boolean {
        if (PinnedSsl.unwrap(error) is PinnedSsl.CertChangedException) return false
        return isConnectPhaseFailure(error)
    }

    /** 并行探测，返回最先通的地址；都不通或超时返回 null。 */
    internal fun firstReachable(urls: List<String>, timeoutMs: Long, probe: (String) -> Boolean): String? {
        if (urls.isEmpty()) return null
        val winner = AtomicReference<String?>(null)
        val remaining = AtomicInteger(urls.size)
        val settled = CountDownLatch(1)
        for (url in urls) {
            Thread({
                val ok = runCatching { probe(url) }.getOrDefault(false)
                if (ok && winner.compareAndSet(null, url)) settled.countDown()
                if (remaining.decrementAndGet() == 0) settled.countDown()
            }, "dsh-lan-probe").apply { isDaemon = true; start() }
        }
        settled.await(timeoutMs, TimeUnit.MILLISECONDS)
        return winner.get()
    }

    internal fun pairOn(
        normalized: String,
        pin: String?,
        code: String,
        deviceName: String,
        requestId: String,
        route: HostRoute,
        remote: RemoteRoute?,
    ): Result {
        val host = Host(name = "pair", baseUrl = normalized, token = "", certFingerprint = pin.orEmpty())
        val via = if (route == HostRoute.REMOTE) "remote" else "lan"
        try {
            HostHttp.execute(
                host,
                HostHttp.DshRequest(
                    method = "POST",
                    path = "/dsh-link/pair",
                    body = pairRequestBody(code, deviceName, via, requestId).toString().toByteArray(),
                    headers = listOf("Content-Type" to "application/json"),
                    connectTimeoutMs = 8_000,
                    readTimeoutMs = 8_000,
                ),
                forceRoute = route,
                remoteOverride = remote,
            ).use { response ->
                val respCode = response.code
                val body = response.body.byteStream().use { BoundedIo.readText(it) }
                if (respCode != 200) {
                    throw PairRequestException(pairFailureFromHttp(respCode, body))
                }
                return parsePairSuccess(normalized, body, deviceName, pin, route)
            }
        } catch (e: RouteRejectedException) {
            // F3：中继明确拒绝（UNKNOWN_KEY / BAD_MAC 等），不删凭据，让用户稍后重试。
            throw PairRequestException(PairFailure.fromRelayCode(e.code, pending = false))
        } catch (t: Throwable) {
            if (t is Exception) {
                val unwrapped = PinnedSsl.unwrap(t)
                // 远程最后一跳的 TLS/HTTP 中断也必须保留链路，不显示局域网地址全部失败。
                if (route == HostRoute.REMOTE && unwrapped is IOException) {
                    val failure = PairFailure.fromException(unwrapped)
                    throw PairRequestException(if (failure.code == PairFailureCode.NETWORK_FAILURE) PairFailure.remoteInterrupted() else failure)
                }
                throw unwrapped
            }
            throw IOException(t.message ?: t.javaClass.simpleName, t)
        }
    }

    /** `via` 仅是给旧插件的自报；新插件按连接来源判定，不看它（插件 RFC §6.1）。 */
    internal fun pairRequestBody(code: String, deviceName: String, via: String, requestId: String): JSONObject =
        JSONObject()
            .put("code", code)
            .put("deviceName", deviceName)
            .put("via", via)
            .put("requestId", requestId)

    /** 批准检查只用设备凭据；实际扫码路由用于展示，后续 GET 可安全重新选路。 */
    fun approvalStateSealed(session: PairingSession): PairApproval = try {
        HostHttp.execute(session.host, approvalRequest(session.host)).use { response ->
            val body = response.body.byteStream().use { BoundedIo.readText(it, BoundedIo.MAX_HEALTH_BODY_BYTES) }
            approvalFromResponseSealed(response.code, body)
        }
    } catch (t: Exception) {
        approvalFromError(t)
    }

    internal fun approvalRequest(host: Host) = HostHttp.DshRequest(
        "GET", "/dsh-link/mobile/sessions",
        headers = listOf("x-dsh-link-token" to host.token),
        connectTimeoutMs = 4_000, readTimeoutMs = 6_000,
    )

    internal fun approvalFromError(error: Exception): PairApproval = when (val unwrapped = PinnedSsl.unwrap(error)) {
        is PinnedSsl.CertChangedException -> PairApproval.IdentityMismatch
        is RouteRejectedException -> PairApproval.RelayReportedRejection(unwrapped.code)
        is java.net.SocketTimeoutException -> PairApproval.Retryable(RetryProblem.HOST_OFFLINE)
        is java.net.UnknownHostException -> PairApproval.Retryable(RetryProblem.NETWORK_FAILURE)
        else -> PairApproval.Retryable(RetryProblem.UNKNOWN)
    }

    internal fun approvalFromResponseSealed(code: Int, body: String?): PairApproval = when {
        code in 200..299 -> PairApproval.Approved
        code == 403 && runCatching { JSONObject(body.orEmpty()).optBoolean("pending") }.getOrDefault(false) -> PairApproval.Pending
        code == 401 -> PairApproval.RejectedByHost
        code == 429 || code == 503 -> PairApproval.Retryable(RetryProblem.BUSY)
        else -> PairApproval.Retryable(RetryProblem.UNKNOWN)
    }

    /** 探测主机：在线、暂时不可达，或证书已失效。 */
    fun probe(host: Host): HostHealth {
        val start = System.currentTimeMillis()
        return try {
            HostHttp.execute(
                host,
                HostHttp.DshRequest("GET", "/dsh-link/health", connectTimeoutMs = 2_500, readTimeoutMs = 2_500),
            ).use { response ->
                if (response.code == 200) {
                    runCatching {
                        response.body.byteStream().use { BoundedIo.readText(it, BoundedIo.MAX_HEALTH_BODY_BYTES) }
                    }
                    HostHealth.Ok(System.currentTimeMillis() - start)
                } else HostHealth.Unreachable
            }
        } catch (t: Throwable) {
            classifyHostHealthError(t)
        }
    }

    /** 探测主机在线状态，返回延迟毫秒数；不可达返回 null。凭据失效时抛出。 */
    fun health(host: Host): Long? = when (val result = probe(host)) {
        is HostHealth.Ok -> result.latencyMs
        HostHealth.Unreachable -> null
        is HostHealth.AuthFailed -> throw result.error
    }

    fun health(baseUrl: String, certFingerprint: String? = null): Long? {
        return health(Host("probe", normalize(baseUrl), "", certFingerprint = certFingerprint.orEmpty()))
    }

    /** 解析成功配对 JSON。`pending=true` 表示主机开启了本机确认，token 已保存但尚未放行。 */
    fun parsePairSuccess(normalized: String, body: String, deviceName: String, pin: String?, route: HostRoute = HostRoute.LAN): Result {
        val o = JSONObject(body)
        return Result(
            normalized,
            o.optString("name", deviceName),
            o.getString("token"),
            o.optString("deviceId"),
            pin.orEmpty(),
            o.optBoolean("pending"),
            runCatching { RemoteRoute.fromPairResponse(o) }.getOrNull(),
            "",
            route,
            runCatching { o.getLong("pendingExpiresAt") }.getOrNull()?.takeIf { it > 0 },
            runCatching { o.getLong("serverNow") }.getOrNull()?.takeIf { it > 0 },
        )
    }

    /** 将配对 HTTP 错误转为用户可读文案。 */
    /** 根据 HTTP 状态码直接构造结构化 PairFailure（不经过 fromException）。 */
    internal fun pairFailureFromHttp(code: Int, body: String): PairFailure {
        return when (code) {
            401 -> PairFailure(PairFailureCode.PAIR_CODE_INVALID, PairRecovery.RESCAN, LocaleManager.strings.pairCodeInvalid)
            409 -> PairFailure(PairFailureCode.SAME_NAME, PairRecovery.RESCAN, LocaleManager.strings.pairNameTaken)
            415 -> PairFailure(PairFailureCode.QR_REMOTE_INVALID, PairRecovery.RESCAN, LocaleManager.strings.remoteCredentialInvalid)
            429 -> PairFailure(PairFailureCode.RATE_LIMITED, PairRecovery.RESCAN, LocaleManager.strings.pairTooManyAttempts)
            in 500..599 -> PairFailure(PairFailureCode.RELAY_UNREACHABLE, PairRecovery.RESCAN, LocaleManager.strings.pairHostUnavailable.format(code))
            else -> PairFailure(PairFailureCode.UNKNOWN, PairRecovery.BACK, LocaleManager.strings.pairFailedHttp.format(code))
        }
    }

    /** 供人类阅读的兜底错误（仅用于旧调用点）。 */
    fun friendlyPairError(code: Int, body: String): String = pairFailureFromHttp(code, body).message
}
