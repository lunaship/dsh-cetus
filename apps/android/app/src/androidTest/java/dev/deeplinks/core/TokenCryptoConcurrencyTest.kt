package dev.deeplinks.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TokenCrypto 并发首生用例（方案第 5 步 D5）：
 * 删掉 Keystore 里的主机密钥后，16 个线程同时首次 `encrypt`，全部 `decrypt` 成功。
 *
 * 背景：`getOrCreateKey` 靠 `@Synchronized` 串行化首次生成；若这个守卫失效
 * （多把密钥 / 生成中途被拿去 init Cipher），表现是间歇的 `InvalidKeyException`
 * 或解密返回 null——配对 token 是访问 dsh 的唯一凭证，这类竞态必须在设备上
 * 用真并发钉死，而不是靠读代码。
 *
 * 注意：用例会删掉 `dsh_hosts_key_v1`（[TokenCrypto.KEY_ALIAS]）。在开发者真机上
 * 手动跑完需要重新配对主机；CI 模拟器数据本就一次性，无副作用。
 */
@RunWith(AndroidJUnit4::class)
class TokenCryptoConcurrencyTest {

    @Test
    fun concurrentFirstEncryptAfterKeyDeletionAllDecrypt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keystore.deleteEntry(TokenCrypto.KEY_ALIAS)

        val threads = 16
        val plaintexts = (0 until threads).map { "host-token-$it-8f3a91c2" }
        val blobs = arrayOfNulls<String>(threads)
        val errors = CopyOnWriteArrayList<Throwable>()
        val startLatch = CountDownLatch(1)

        val workers = (0 until threads).map { index ->
            Thread {
                startLatch.await()
                try {
                    blobs[index] = TokenCrypto.encrypt(context, plaintexts[index])
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }.apply { start() }
        }
        startLatch.countDown()
        workers.forEach { it.join(15_000) }

        assertTrue("并发首次 encrypt 不应抛错：${errors.map { it.toString() }}", errors.isEmpty())
        blobs.forEachIndexed { index, blob ->
            assertNotNull("第 $index 个线程应产出密文", blob)
            assertEquals("第 $index 个线程的密文应能解回原文", plaintexts[index], TokenCrypto.decrypt(context, blob!!))
        }
    }
}
