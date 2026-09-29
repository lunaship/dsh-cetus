package dev.deeplinks.core

import dev.deeplinks.core.remote.HostRoute
import dev.deeplinks.core.remote.RemoteRoute
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
    fun pairWithQr(qr: PairingQr, deviceName: String, requestId: String = UUID.randomUUID().toString()): Result {
        val pin = qr.certFingerprint.takeIf { it.isNotBlank() }
        val remote = qr.remote
        if (remote == null || pin == null) {
            var last: Exception? = null
            for (url in qr.urls) {
                try {
                    return pairOn(normalize(url), pin, qr.code, deviceName, requestId, HostRoute.LAN, null)
                } catch (e: Exception) {
                    last = e
                }
            }
            throw last ?: IOException("no address in QR")
        }
        val reachable = firstReachable(qr.urls.map(::normalize), LAN_PROBE_WAIT_MS) { HostHttp.probeLanUrl(it, pin) }
        if (reachable != null) {
            try {
                return pairOn(reachable, pin, qr.code, deviceName, requestId, HostRoute.LAN, null)
            } catch (e: Exception) {
                if (!canFallBackToRemote(e)) throw e
            }
        }
        // 远程首配时 baseUrl 只用来拼请求路径，隧道不连它
        val base = qr.urls.firstOrNull()?.let(::normalize) ?: "https://127.0.0.1:18640"
        return pairOn(base, pin, qr.code, deviceName, requestId, HostRoute.REMOTE, remote)
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

    private fun pairOn(
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
                val body = response.body?.byteStream()?.use { BoundedIo.readText(it) } ?: ""
                if (respCode != 200) throw Exception(friendlyPairError(respCode, body))
                return parsePairSuccess(normalized, body, deviceName, pin)
            }
        } catch (t: Throwable) {
            if (t is Exception) throw PinnedSsl.unwrap(t)
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
                        response.body?.byteStream()?.use { BoundedIo.readText(it, BoundedIo.MAX_HEALTH_BODY_BYTES) }
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
    internal fun parsePairSuccess(normalized: String, body: String, deviceName: String, pin: String?): Result {
        val o = JSONObject(body)
        return Result(
            normalized,
            o.optString("name", deviceName),
            o.getString("token"),
            o.optString("deviceId"),
            pin.orEmpty(),
            o.optBoolean("pending"),
            runCatching { RemoteRoute.fromPairResponse(o) }.getOrNull(),
        )
    }

    /** 将配对 HTTP 错误转为用户可读文案。 */
    fun friendlyPairError(code: Int, body: String): String {
        val hint = runCatching {
            JSONObject(body).let { o -> if (o.isNull("error")) null else o.optString("error").takeIf { it.isNotBlank() } }
        }.getOrNull()
        return when (code) {
            401 -> hint ?: LocaleManager.strings.pairCodeInvalid
            409 -> hint ?: LocaleManager.strings.pairNameTaken
            415 -> LocaleManager.strings.pairBadRequest
            429 -> hint ?: LocaleManager.strings.pairTooManyAttempts
            in 500..599 -> hint ?: LocaleManager.strings.pairHostUnavailable.format(code)
            else -> hint ?: LocaleManager.strings.pairFailedHttp.format(code)
        }
    }
}
