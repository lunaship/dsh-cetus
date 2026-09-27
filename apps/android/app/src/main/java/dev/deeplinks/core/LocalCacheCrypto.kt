package dev.deeplinks.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 字节级编解码（本地缓存落盘前后各走一次；测试用恒等实现）。decode 失败返回 null。 */
interface ByteCodec {
    fun encode(plain: ByteArray): ByteArray
    fun decode(blob: ByteArray): ByteArray?
}

/**
 * 本地会话快照加密 —— Android Keystore AES-256-GCM，与 [TokenCrypto] 分开的独立密钥。
 *
 * 快照里是对话正文（可能含代码与密钥片段），虽在应用私有目录且禁用了备份，仍按凭据同级处理：
 * 密钥不可导出，换机 / 清密钥后旧快照解密失败，按「没有缓存」处理即可。
 * 格式：12 字节 IV + 密文（含 16 字节认证标签），不做 Base64。
 */
object LocalCacheCrypto : ByteCodec {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "dsh_local_cache_key_v1"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    override fun encode(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decode(blob: ByteArray): ByteArray? {
        if (blob.size <= IV_BYTES) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, blob, 0, IV_BYTES))
            cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
