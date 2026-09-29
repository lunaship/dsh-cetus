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
)

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
    val remote = runCatching { RemoteRoute.fromQr(payload) }.getOrNull()
    val fp = payload.optString("certFingerprint").trim()
    // 远程首配的内层 TLS 仍钉扎插件证书：没有指纹，远程这条路就走不了
    val usableRemote = remote?.takeIf { fp.isNotEmpty() }
    if (code.isEmpty() || (urls.isEmpty() && usableRemote == null)) return PairingQrResult.Invalid
    val name = payload.optString("name", "dsh").ifBlank { "dsh" }
    return PairingQrResult.Ok(PairingQr(code, urls, name, fp, usableRemote))
}

/** 配对成功后的本机记录：远程是同一台电脑的另一条路，不另起「· 云」设备（RFC §10.4 第 4 条）。 */
fun hostFromPair(name: String, result: PairClient.Result): Host {
    val host = Host(
        name = name,
        baseUrl = result.baseUrl,
        token = result.token,
        deviceId = result.deviceId,
        certFingerprint = result.certFingerprint,
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
