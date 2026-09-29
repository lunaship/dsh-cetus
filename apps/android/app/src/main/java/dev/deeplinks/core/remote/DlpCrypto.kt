package dev.deeplinks.core.remote

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** DLP/1 的逐字节密码学合同；协议常量集中在此处。 */
object DlpCrypto {
    const val VERSION = 1
    private val routePrefix = "DLP1 route\u0000".toByteArray(Charsets.US_ASCII)
    private val registerPrefix = "DLP1 host_register\u0000".toByteArray(Charsets.US_ASCII)
    private val acceptPrefix = "DLP1 host_accept\u0000".toByteArray(Charsets.US_ASCII)
    private val clientPrefix = "DLP1 client_open\u0000".toByteArray(Charsets.US_ASCII)
    private val deviceKeyPrefix = "DLP1 device key\u0000".toByteArray(Charsets.US_ASCII)
    private val bootstrapIdInfo = "DLP1 bootstrap id".toByteArray(Charsets.US_ASCII)
    private val bootstrapKeyInfo = "DLP1 bootstrap key".toByteArray(Charsets.US_ASCII)

    fun routeId(hostPub: ByteArray): ByteArray {
        requireLength(hostPub, 32, "host public key")
        return MessageDigestHolder.sha256(routePrefix + hostPub).copyOf(16)
    }

    fun registerTranscript(ch: ByteArray, hostPub: ByteArray, version: Int = VERSION): ByteArray {
        requireVersion(version)
        requireLength(ch, 32, "challenge")
        requireLength(hostPub, 32, "host public key")
        return registerPrefix + byteArrayOf(version.toByte()) + ch + hostPub
    }

    fun acceptTranscript(ch: ByteArray, hostPub: ByteArray, sid: ByteArray, version: Int = VERSION): ByteArray {
        requireVersion(version)
        requireLength(ch, 32, "challenge")
        requireLength(hostPub, 32, "host public key")
        requireLength(sid, 16, "sid")
        return acceptPrefix + byteArrayOf(version.toByte()) + ch + hostPub + sid
    }

    fun clientTranscript(route: ByteArray, kind: String, key: ByteArray, ts: Long, nonce: ByteArray, version: Int = VERSION): ByteArray {
        requireVersion(version)
        requireLength(route, 16, "route")
        requireLength(key, 16, "key")
        requireLength(nonce, 16, "nonce")
        require(ts >= 0) { "timestamp must be non-negative" }
        val kindByte = when (kind) {
            "device" -> 1
            "bootstrap" -> 2
            else -> throw IllegalArgumentException("invalid client kind")
        }
        val timestamp = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(ts).array()
        return clientPrefix + byteArrayOf(version.toByte()) + route + byteArrayOf(kindByte.toByte()) + key + timestamp + nonce
    }

    fun deviceRelayKey(keySeed: ByteArray, relayHandle: ByteArray): ByteArray {
        requireLength(keySeed, 32, "key seed")
        requireLength(relayHandle, 16, "relay handle")
        return hmacSha256(keySeed, deviceKeyPrefix + relayHandle)
    }

    fun clientMac(key: ByteArray, transcript: ByteArray): ByteArray {
        requireLength(key, 32, "client key")
        return hmacSha256(key, transcript)
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32)) { "invalid HKDF output length" }
        val prk = hmacSha256(salt, ikm)
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
            val count = minOf(previous.size, length - offset)
            previous.copyInto(output, offset, 0, count)
            offset += count
            counter++
        }
        return output
    }

    fun bootstrapKeys(seed: ByteArray, route: ByteArray): Pair<ByteArray, ByteArray> =
        hkdf(seed, route, bootstrapIdInfo, 16) to hkdf(seed, route, bootstrapKeyInfo, 32)

    fun base64Url(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun hmacSha256(key: ByteArray, input: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(input)
    }

    private fun requireLength(bytes: ByteArray, size: Int, name: String) {
        require(bytes.size == size) { "$name must be $size bytes" }
    }

    private fun requireVersion(version: Int) {
        require(version == VERSION) { "unsupported version" }
    }

    private object MessageDigestHolder {
        fun sha256(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
