package dev.deeplinks.core

import dev.deeplinks.core.remote.HostRoute
import dev.deeplinks.core.remote.ReleasingBody
import dev.deeplinks.core.remote.RemoteGate
import dev.deeplinks.core.remote.RemoteRoute
import dev.deeplinks.core.remote.RouteBusyException
import dev.deeplinks.core.remote.RouteConnectException
import dev.deeplinks.core.remote.RouteRejectedException
import dev.deeplinks.core.remote.RouteSelector
import dev.deeplinks.core.remote.WebSocketTunnelSocketFactory
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * 该异常是否发生在「连接尚未建立」阶段（RFC §7.2 第 7 条）。
 *
 * 只有连接尚未建立时切换到另一条路才是安全的——请求体一旦写出，重放到另一条路径可能造成
 * 非幂等请求重复执行。OkHttp 把连接与请求写合并在 execute() 里，这里按异常类型区分：
 * - 远程会合失败（RouteConnectException：中继不可达、电脑离线、ready 前被拒）：建立期；
 * - Connect/UnknownHost/NoRoute/PortUnreachable/SSL 握手类：建立期；
 * - SocketTimeoutException：只有「connect timed out」视为建立期（读超时不可重放）。
 */
internal fun isConnectPhaseFailure(error: Throwable): Boolean {
    var cur: Throwable? = error
    while (cur != null) {
        when (cur) {
            is RouteConnectException,
            is ConnectException,
            is UnknownHostException,
            is NoRouteToHostException,
            is PortUnreachableException,
            is SSLException,
            -> return true
            is SocketTimeoutException -> {
                if (cur.message.orEmpty().contains("connect", ignoreCase = true)) return true
            }
        }
        cur = cur.cause
    }
    return false
}

/**
 * 这次失败是不是「稍后在同一条路上重试」（DEVICE_LIMIT / SERVER_BUSY / RATE_LIMITED / 本地排队）。
 * 沿因果链查找 [RouteBusyException]（RFC §5.7 错误码表）。
 */
internal fun isBusy(error: Throwable): Boolean =
    generateSequence<Throwable>(error) { it.cause }.any { it is RouteBusyException }

/** 该异常（或因果链里）是不是内层 TLS 证书不符——硬停止，绝不重试。 */
private fun isCertChanged(error: Throwable): Boolean =
    generateSequence<Throwable>(error) { it.cause }.any { PinnedSsl.unwrap(it) is PinnedSsl.CertChangedException }

/**
 * LAN/公网身份校验：有指纹则要求合法格式（64 位十六进制）；
 * 无指纹仅允许公网主机走系统 PKI（私网/回环 fail-closed）。
 */
internal fun validateLanIdentity(baseUrl: String, normalizedPin: String) {
    if (normalizedPin.isEmpty()) {
        // 私网/回环主机必须钉死证书；空指纹不得静默回退到系统 PKI（fail-closed）
        if (PinnedSsl.shouldPin(baseUrl)) throw PinnedSsl.CertChangedException()
        return
    }
    PinnedSsl.requireValidPin(normalizedPin)
}

/**
 * 按候选顺序尝试（RFC §7.2）：
 *
 * - 任一候选返回结果即终止，绝不因响应期错误切换路径；
 * - 证书变更（CertChangedException）是认证失败而非传输故障，立即抛出（§7.2 第 8 条）；
 * - 繁忙（[isBusy]）立即抛出：远端只是暂时满了，换路解决不了，交给 executeRemote 退避重试；
 * - 只有建立期失败才换下一条路（[isConnectPhaseFailure]）；之后的失败直接抛出，不重放。
 *   `hasBody` 保留在签名里记录调用意图，规则对两类请求一致。
 */
internal fun <R> attemptWithFailover(
    key: String,
    routes: List<HostRoute>,
    @Suppress("UNUSED_PARAMETER") hasBody: Boolean,
    attempt: (HostRoute) -> R,
): R {
    // 都失败时报首选路的错误：它是按当前网络选出来的那条，原因最能说明问题
    // （例如外面时远程被拒的原因，而不是回退去试局域网得到的连接错误）
    var first: IOException? = null
    for (route in routes) {
        try {
            return attempt(route)
        } catch (e: IOException) {
            if (PinnedSsl.unwrap(e) is PinnedSsl.CertChangedException) throw e
            // 繁忙不是「这条路不通」：远端在上限里，换局域网毫无意义（人在外面必然失败），
            // 交给 executeRemote 的退避重试（RFC §5.7）。也不清选路缓存。
            if (isBusy(e)) throw e
            if (first == null) first = e
            // 建立期之后的失败不换路：有请求体的可能已写出，GET 的读超时换路也只是把等待翻倍
            if (!isConnectPhaseFailure(e)) throw e
        }
    }
    throw first ?: IOException("no route to host")
}

/**
 * OkHttp 传输引擎。
 *
 * - 局域网：按证书指纹钉死的 SSLSocketFactory；无指纹仅允许公网主机走系统 CA。
 * - 远程（DLP/1）：[WebSocketTunnelSocketFactory] 建外层 WSS 与会合，内层 TLS 仍钉扎插件证书，
 *   OkHttp 在隧道上跑 HTTP/1.1；连接池复用已建隧道（RFC §4.2）。
 * - 选路见 [RouteSelector]；换路规则见 [attemptWithFailover]。
 */
object HostHttp {

    class DshRequest(
        val method: String,
        val path: String,
        val body: ByteArray? = null,
        val headers: List<Pair<String, String>> = emptyList(),
        val connectTimeoutMs: Int = 8_000,
        val readTimeoutMs: Int = 12_000,
        /** SSE 等长连接：不经过 [RemoteGate]，否则会一直占着并发名额。 */
        val streaming: Boolean = false,
    )

    private val selector get() = RouteSelector.shared

    /** 路由缓存、时钟偏移、「最近走哪条路」的键：同一台电脑（地址 + 指纹 + 设备）。 */
    internal fun routeKey(host: Host): String = lanKey(host) + "\u001f" + host.deviceId

    /** 最近一次成功的请求走的是不是远程（界面上的「局域网 / 远程」）。 */
    fun isViaRemote(host: Host): Boolean = selector.lastRoute(routeKey(host)) == HostRoute.REMOTE

    /**
     * 执行请求并返回 [Response]。调用方负责 close。
     * 非 2xx 响应码不是异常（不触发换路）；只有建立期 IOException 才会。
     *
     * @param forceRoute 只走这一条路（配对、自检用）
     * @param remoteOverride 远程时用这条路由代替 host 里存的（远程首配的 bootstrap 路由）
     */
    internal fun execute(
        host: Host,
        request: DshRequest,
        forceRoute: HostRoute? = null,
        remoteOverride: RemoteRoute? = null,
        onCall: ((Call) -> Unit)? = null,
    ): Response {
        val url = host.baseUrl.trimEnd('/') + request.path
        // 明文 HTTP 不允许：局域网自签证书场景必须走钉死指纹的 HTTPS
        require(url.startsWith("https://")) { "拒绝明文 HTTP，仅支持 HTTPS" }
        val key = routeKey(host)
        val remote = remoteOverride ?: host.remoteRoute()
        val routes = forceRoute?.let { listOf(it) } ?: selector.order(key, remote != null) { probeLan(host) }
        return attemptWithFailover(key, routes, request.body != null) { route ->
            try {
                val response = if (route == HostRoute.REMOTE) {
                    executeRemote(host, remote ?: throw IOException("no remote route"), request, url, key, onCall)
                } else {
                    newCall(clientFor(host, request), url, request, onCall).execute()
                }
                if (forceRoute == null) selector.noteSuccess(key, route)
                response
            } catch (e: IOException) {
                // 繁忙不清选路缓存：这条路是通的，只是暂时满了（R2）。
                if (forceRoute == null && isConnectPhaseFailure(e) && !isBusy(e)) selector.forget(key)
                throw e
            }
        }
    }

    /**
     * 远程一次请求（RFC §5.7、§7.2）：
     * - 非 [DshRequest.streaming] 的短请求先过 [RemoteGate]，名额在响应体关闭时归还；
     * - 隧道建立阶段的繁忙（DEVICE_LIMIT / SERVER_BUSY / RATE_LIMITED）按 400 / 1200 / 2500ms（±抖动）
     *   退避重试，最多 3 次，不换路、不清缓存；
     * - CLOCK_SKEW 按电脑给的 hostNow 记偏移后重试一次，REPLAY 换 nonce 重试一次；
     * - 复用连接被远端关掉时，GET 自动换新连接重试一次（R5；POST 不重试，见 §7.2）。
     *
     * 其余拒绝码只抛给上层做提示，绝不据此删除凭据（§7.4）。
     */
    private fun executeRemote(
        host: Host,
        remote: RemoteRoute,
        request: DshRequest,
        url: String,
        key: String,
        onCall: ((Call) -> Unit)?,
    ): Response {
        val gated = !request.streaming
        if (gated && !RemoteGate.acquire(key, GATE_WAIT_MS)) throw RouteBusyException("LOCAL_QUEUE")
        var released = false
        val release = {
            if (gated && !released) {
                released = true
                RemoteGate.release(key)
            }
        }
        try {
            val response = executeRemoteCall(host, remote, request, url, key, onCall)
            return response.newBuilder().body(ReleasingBody(response.body, release)).build()
        } catch (e: Throwable) {
            release()
            throw e
        }
    }

    private fun executeRemoteCall(
        host: Host,
        remote: RemoteRoute,
        request: DshRequest,
        url: String,
        key: String,
        onCall: ((Call) -> Unit)?,
    ): Response {
        var clockRetried = false
        var busyAttempt = 0
        var reuseRetried = false
        while (true) {
            val marker = ReuseMarker()
            try {
                return newCall(remoteClient(host, remote, key, request), url, request, onCall, marker).execute()
            } catch (e: IOException) {
                // 繁忙：只在隧道建立阶段、请求体尚未写出时退避重试，最多 3 次（R2）。
                if (isBusy(e) && busyAttempt < BUSY_BACKOFF_MS.size) {
                    Thread.sleep(busyBackoffMs(busyAttempt))
                    busyAttempt++
                    continue
                }
                // R5：池里的旧连接被远端关掉（这次 call 没经历 connectStart）时，GET 换新连接再试一次。
                if (request.method == "GET" && !reuseRetried && !marker.connected() && !isCertChanged(e)) {
                    reuseRetried = true
                    remoteClient(host, remote, key, request).connectionPool.evictAll()
                    continue
                }
                val rejected = generateSequence<Throwable>(e) { it.cause }
                    .filterIsInstance<RouteRejectedException>().firstOrNull()
                if (!clockRetried && rejected != null) {
                    when (rejected.code) {
                        "CLOCK_SKEW" -> rejected.hostNow?.let { selector.noteHostNow(key, it) } ?: throw e
                        "REPLAY" -> Unit
                        else -> throw e
                    }
                    clockRetried = true
                    continue
                }
                throw e
            }
        }
    }

    /** 退避：400 / 1200 / 2500ms，各乘 0.7–1.3 抖动（R2）。 */
    private fun busyBackoffMs(attempt: Int): Long =
        (BUSY_BACKOFF_MS[attempt] * (0.7 + Random.nextDouble() * 0.6)).toLong()

    private fun newCall(
        client: OkHttpClient,
        url: String,
        request: DshRequest,
        onCall: ((Call) -> Unit)?,
        marker: ReuseMarker? = null,
    ): Call {
        val builder = Request.Builder().url(url)
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        when {
            request.body != null -> builder.method(
                request.method,
                request.body.toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
            request.method == "GET" -> builder.get()
            else -> builder.method(request.method, null)
        }
        if (marker != null) builder.tag(ReuseMarker::class.java, marker)
        return client.newCall(builder.build()).also { onCall?.invoke(it) }
    }

    /** 网络变化：丢掉空闲连接，下一次请求按新网络重新选路（RFC §7.2 第 1 条）。 */
    fun onNetworkChanged() {
        selector.onNetworkChanged()
        lanClients.values.forEach { it.connectionPool.evictAll() }
        remoteClients.values.forEach { it.connectionPool.evictAll() }
    }

    /** 回到前台：清掉空闲的远程连接，避免继续使用可能已被远端关掉的隧道（R5）。 */
    fun evictIdleRemote() {
        remoteClients.values.forEach { it.connectionPool.evictAll() }
    }

    // ===== 局域网 =====

    /** 同主机（URL+指纹）复用连接池。 */
    private val lanClients = ConcurrentHashMap<String, OkHttpClient>()

    private fun lanKey(host: Host): String =
        PinnedSsl.normalizeUrl(host.baseUrl).trimEnd('/').lowercase() +
            "\u001f" + PinnedSsl.normalizeFingerprint(host.certFingerprint)

    private fun clientFor(host: Host, request: DshRequest): OkHttpClient =
        lanClients.computeIfAbsent(lanKey(host)) { lanClient(host) }.newBuilder()
            .connectTimeout(request.connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(request.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()

    private fun lanClient(host: Host): OkHttpClient {
        val pin = PinnedSsl.normalizeFingerprint(host.certFingerprint)
        validateLanIdentity(host.baseUrl, pin)
        val builder = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 30_000, TimeUnit.MILLISECONDS))
            .retryOnConnectionFailure(true)
        if (pin.isEmpty()) return builder.build()
        val trustManager: X509TrustManager = PinnedSsl.pinnedTrustManager(pin)
        return builder
            .sslSocketFactory(pinnedContext(trustManager).socketFactory, trustManager)
            // 身份 = 叶证书指纹钉死，跳过主机名校验（与旧 PinnedSsl.apply 一致）
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .build()
    }

    /**
     * 局域网探测（RFC §7.2 第 3、4 条）：TCP 连接（800 毫秒）+ 钉扎证书的 TLS 握手，总预算 1.2 秒，
     * 不发送任何 HTTP、配对码或 Token。证书不符 = 这个地址不是这台电脑（咖啡馆同网段、DHCP
     * 换了机器），算不通，交给远程——两条路钉扎同一张证书，不存在降级攻击面。
     */
    internal fun probeLan(host: Host): Boolean = probeLanUrl(host.baseUrl, host.certFingerprint)

    internal fun probeLanUrl(baseUrl: String, certFingerprint: String): Boolean {
        val pin = PinnedSsl.normalizeFingerprint(certFingerprint)
        if (pin.isEmpty()) return false
        val uri = runCatching { URI(PinnedSsl.normalizeUrl(baseUrl)) }.getOrNull() ?: return false
        val hostName = uri.host ?: return false
        val port = if (uri.port > 0) uri.port else 443
        val started = System.currentTimeMillis()
        return try {
            Socket().use { raw ->
                raw.connect(InetSocketAddress(hostName, port), LAN_PROBE_CONNECT_MS)
                val left = LAN_PROBE_BUDGET_MS - (System.currentTimeMillis() - started).toInt()
                if (left <= 0) return false
                raw.soTimeout = left
                val tls = pinnedContext(PinnedSsl.pinnedTrustManager(pin)).socketFactory
                    .createSocket(raw, hostName, port, false) as SSLSocket
                tls.use { it.startHandshake() }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    // ===== 远程（DLP/1） =====

    /** 同一条远程路由（+ 时钟偏移）复用隧道连接池。 */
    private val remoteClients = ConcurrentHashMap<String, OkHttpClient>()

    private fun remoteClient(host: Host, remote: RemoteRoute, key: String, request: DshRequest): OkHttpClient {
        val offset = selector.clockOffsetSec(key)
        val cacheKey = lanKey(host) + "\u001f" + remote.endpoint + "\u001f" + DlpKey.of(remote) + "\u001f" + offset
        // 远程多了外层 WSS 与会合两跳：建立期与读取期都放宽，不让调用方各自判断
        return remoteClients.computeIfAbsent(cacheKey) { buildRemoteClient(host, remote, offset) }.newBuilder()
            .connectTimeout(maxOf(request.connectTimeoutMs, REMOTE_CONNECT_MS).toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(maxOf(request.readTimeoutMs, REMOTE_READ_MS).toLong(), TimeUnit.MILLISECONDS)
            .build()
    }

    /** 生产配置的远程客户端；抽成 internal 让复用测试直接测这一份配置（R6）。 */
    internal fun buildRemoteClient(host: Host, remote: RemoteRoute, offset: Long): OkHttpClient {
        val innerPin = PinnedSsl.normalizeFingerprint(host.certFingerprint)
        if (innerPin.isBlank()) throw IOException("missing inner TLS pin")
        PinnedSsl.requireValidPin(innerPin)
        val trustManager = PinnedSsl.pinnedTrustManager(innerPin)
        return OkHttpClient.Builder()
            // 裸连接 = 隧道：connect() 内完成外层 WSS + 会合，之后内层 TLS 在它上面进行
            .socketFactory(WebSocketTunnelSocketFactory(remote, offset))
            .sslSocketFactory(pinnedContext(trustManager).socketFactory, trustManager)
            // 内层身份 = 插件叶证书指纹；外层身份由隧道按系统 CA 或 outerPin 校验
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            // 人在外面时 URL 里的局域网主机名可能解析不了；隧道根本不用这个地址，别让 DNS 挡路
            .dns(TunnelDns)
            .proxy(Proxy.NO_PROXY)
            // 3 个空闲 + 4 个在途 + 2 条 SSE ≤ 12，与 R3 的单设备并发上限一起改；50s 要短于插件的 65s。
            .connectionPool(ConnectionPool(3, 50, TimeUnit.SECONDS))
            // 不交给 OkHttp 自动重放（RFC §7.2：POST 不得重试）。GET 的复用重试由 executeRemoteCall 精确控制（R5）。
            .retryOnConnectionFailure(false)
            .eventListenerFactory(
                EventListener.Factory { call ->
                    object : EventListener() {
                        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                            call.request().tag(ReuseMarker::class.java)?.markConnected()
                        }
                    }
                },
            )
            .build()
    }

    private object TunnelDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            listOf(InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1)))
    }

    /** 缓存键里不放设备密钥本身，只放它的摘要。 */
    private object DlpKey {
        fun of(remote: RemoteRoute): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(remote.routeId + remote.keyId + remote.key + remote.kind.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(24)
    }

    private fun pinnedContext(trustManager: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }

    /**
     * R5：记录这次 call 是否走了新连接。OkHttp 收到 `connectStart` 才置位；
     * 没置位说明用的是连接池里的旧隧道（可能已被远端关掉）。
     */
    internal class ReuseMarker {
        private val fresh = AtomicReference(false)
        fun markConnected() { fresh.set(true) }
        fun connected(): Boolean = fresh.get()
    }

    private const val LAN_PROBE_CONNECT_MS = 800
    private const val LAN_PROBE_BUDGET_MS = 1_200
    private const val REMOTE_CONNECT_MS = 15_000
    private const val REMOTE_READ_MS = 30_000

    /** 短请求排队等空闲远程名额的上限；超时抛 LOCAL_QUEUE（R2）。 */
    private const val GATE_WAIT_MS = 15_000L

    /** 繁忙退避：400 / 1200 / 2500ms（各乘 0.7–1.3 抖动），最多重试 3 次（R2）。 */
    private val BUSY_BACKOFF_MS = longArrayOf(400, 1_200, 2_500)
}
