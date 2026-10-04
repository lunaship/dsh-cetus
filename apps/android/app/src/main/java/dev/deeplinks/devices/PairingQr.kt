package dev.deeplinks.devices

import dev.deeplinks.core.Host
import dev.deeplinks.core.PairClient
import dev.deeplinks.core.remote.RemoteRoute
import org.json.JSONArray
import org.json.JSONObject

data class PairingQr(
    val code: String,
    val urls: List<String>,
    val name: String,
    val certFingerprint: String,
    /** 远程首配路由（码里的 `remote`，已派生 bootstrap id/key）；电脑没开远程时为 null。 */
    val remote: RemoteRoute? = null,
    /** 二维码签发时间（Unix 毫秒），可选。 */
    val issuedAt: Long? = null,
    /** 二维码过期时间（Unix 毫秒），可选；null 表示旧码或未设置。 */
    val expiresAt: Long? = null,
    /** 保留错误原因；坏 remote 仍允许合法 LAN 配对。 */
    val remoteCapability: QrRemoteCapability = if (remote == null) QrRemoteCapability.ABSENT else QrRemoteCapability.VALID,
)

enum class QrRemoteCapability { ABSENT, VALID, INVALID, LEGACY }

sealed class PairingQrResult {
    data class Ok(val qr: PairingQr) : PairingQrResult()
    data object NotDsh : PairingQrResult()
    data object Invalid : PairingQrResult()
}

/**
 * 解析手机连接码。旧插件的 `relay`（DLR/1）键不再理会：那套中继已下线。
 * `remote` 写坏了只丢远程能力、仍按局域网码处理——不因为一个可选字段拒掉整张码。
 */
fun parsePairingQr(text: String): PairingQrResult {
    val payload = runCatching { JSONObject(text) }.getOrNull() ?: return PairingQrResult.NotDsh
    if (payload.optString("type") != "dsh-link") return PairingQrResult.NotDsh
    val code = payload.optString("pairingCode", payload.optString("code")).trim()
    val urls = stringList(payload.optJSONArray("urls")) ?: emptyList()
    val fp = payload.optString("certFingerprint").trim()
    val remote = runCatching { RemoteRoute.fromQr(payload) }.getOrNull()?.takeIf { fp.isNotEmpty() }
    val capability = when {
        payload.has("remote") -> if (remote == null) QrRemoteCapability.INVALID else QrRemoteCapability.VALID
        payload.has("relay") -> QrRemoteCapability.LEGACY
        else -> QrRemoteCapability.ABSENT
    }
    // 可选字段缺失兼容旧码；出现时只接受整数和正确顺序，不依赖手机 wall clock 拒码。
    fun timestamp(key: String): Long? {
        val value = payload.opt(key)
        return when (value) {
            is Long -> value.takeIf { it > 0 }
            is Int -> value.toLong().takeIf { it > 0 }
            else -> null
        }
    }
    val issuedAt = timestamp("issuedAt")
    val expiresAt = timestamp("expiresAt")
    if ((payload.has("issuedAt") && issuedAt == null) || (payload.has("expiresAt") && expiresAt == null) ||
        (issuedAt != null && expiresAt != null && issuedAt >= expiresAt)) return PairingQrResult.Invalid
    if (code.isEmpty() || (urls.isEmpty() && remote == null)) return PairingQrResult.Invalid

    val name = payload.optString("name", "dsh").ifBlank { "dsh" }
    return PairingQrResult.Ok(PairingQr(code, urls, name, fp, remote, issuedAt, expiresAt, capability))
}

/** 配对成功后的本机记录：远程是同一台电脑的另一条路，不另起「· 云」设备（RFC §10.4 第 4 条）。 */
fun hostFromPair(name: String, result: PairClient.Result): Host {
    val host = Host(
        name = name,
        baseUrl = result.baseUrl,
        token = result.token,
        deviceId = result.deviceId,
        certFingerprint = result.certFingerprint,
        tailnetUrl = result.tailnetUrl,
    )
    return result.remote?.let(host::withRemote) ?: host
}

private fun stringList(arr: JSONArray?): List<String>? {
    if (arr == null) return null
    val out = ArrayList<String>(arr.length())
    for (i in 0 until arr.length()) {
        val value = arr.opt(i)
        if (value !is String) return null
        val text = value.trim()
        if (text.isEmpty()) return null
        out.add(text)
    }
    return out
}
