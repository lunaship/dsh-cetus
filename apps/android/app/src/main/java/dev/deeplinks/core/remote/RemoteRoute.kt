package dev.deeplinks.core.remote

import java.util.Base64
import org.json.JSONObject

data class RemoteRoute(
    val endpoint: String,
    val routeId: ByteArray,
    val keyId: ByteArray,
    val key: ByteArray,
    val kind: String,
    val outerPin: String = "",
) {
    init {
        require(routeId.size == 16)
        require(keyId.size == 16)
        require(key.size == 32)
        require(kind == "device" || kind == "bootstrap")
    }

    companion object {
        fun fromQr(payload: JSONObject): RemoteRoute? =
            payload.optJSONObject("remote")?.let { parseQrRemote(it) }

        fun fromPairResponse(payload: JSONObject): RemoteRoute? =
            payload.optJSONObject("remote")?.let { parseDeviceRemote(it) }

        /** Host 里存的是 b64u 文本；字段不全或不合法时视为没有远程能力。 */
        fun fromStored(endpoint: String, routeId: String, handle: String, key: String, outerPin: String): RemoteRoute? {
            if (endpoint.isBlank() || routeId.isBlank() || handle.isBlank() || key.isBlank()) return null
            val remote = JSONObject().put("e", endpoint).put("r", routeId).put("h", handle).put("k", key)
            if (outerPin.isNotBlank()) remote.put("p", outerPin)
            return runCatching { parseDeviceRemote(remote) }.getOrNull()
        }

        /** null = 键存在且清除；未提供 = 保留已有远程字段。 */
        fun bootstrapUpdate(payload: JSONObject): BootstrapRemoteUpdate {
            if (!payload.has("remote")) return BootstrapRemoteUpdate(present = false, route = null)
            if (payload.isNull("remote")) return BootstrapRemoteUpdate(present = true, route = null)
            val remote = payload.optJSONObject("remote") ?: throw IllegalArgumentException("remote must be an object or null")
            return BootstrapRemoteUpdate(present = true, route = parseDeviceRemote(remote))
        }

        private fun parseQrRemote(remote: JSONObject): RemoteRoute {
            val endpoint = endpoint(remote.getString("e"))
            val routeId = decode(remote.getString("r"), 16)
            val seed = decode(remote.getString("s"), 16)
            val (bootstrapId, bootstrapKey) = DlpCrypto.bootstrapKeys(seed, routeId)
            return RemoteRoute(endpoint, routeId, bootstrapId, bootstrapKey, "bootstrap", outerPin(remote))
        }

        private fun parseDeviceRemote(remote: JSONObject): RemoteRoute {
            val endpoint = endpoint(remote.getString("e"))
            val routeId = decode(remote.getString("r"), 16)
            val handle = decode(remote.getString("h"), 16)
            val key = decode(remote.getString("k"), 32)
            return RemoteRoute(endpoint, routeId, handle, key, "device", outerPin(remote))
        }

        private fun endpoint(value: String): String {
            val uri = runCatching { java.net.URI(value) }.getOrNull() ?: throw IllegalArgumentException("invalid remote endpoint")
            require(uri.scheme == "wss" && !uri.host.isNullOrBlank()) { "remote endpoint must use wss" }
            return value
        }

        private fun outerPin(remote: JSONObject): String {
            if (!remote.has("p") || remote.isNull("p")) return ""
            val pin = remote.getString("p")
            require(pin.matches(Regex("[0-9a-f]{64}"))) { "invalid outer TLS pin" }
            return pin
        }

        private fun decode(value: String, length: Int): ByteArray {
            require(value.isNotEmpty() && !value.contains('=')) { "invalid base64url" }
            val decoded = try { Base64.getUrlDecoder().decode(value) } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("invalid base64url")
            }
            require(decoded.size == length && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value) {
                "invalid base64url length"
            }
            return decoded
        }
    }
}

data class BootstrapRemoteUpdate(val present: Boolean, val route: RemoteRoute?)
