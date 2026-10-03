package dev.deeplinks.core

import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I3.4 证书指纹共享向量（testdata/tls-fingerprint/vectors.json）：
 * 与插件 certFingerprintSha256、iOS DLSecurity CertificateFingerprint.sha256
 * 三端共用同一份文件，保证指纹算法与格式逐位一致。
 */
class TlsFingerprintVectorsTest {

    @Test
    fun `certificate vectors match expected fingerprints`() {
        val vectors = vectorsJson().getJSONArray("vectors")
        assertTrue("向量清单太短，可能没读到文件", vectors.length() >= 3)
        val factory = CertificateFactory.getInstance("X.509")
        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            val der = Base64.getDecoder().decode(vector.getString("certDerBase64"))
            val cert = factory.generateCertificate(der.inputStream()) as X509Certificate
            assertEquals(vector.getString("name"), vector.getString("fingerprint"), PinnedSsl.fingerprintOf(cert))
        }
    }

    @Test
    fun `normalize cases match the shared rule`() {
        val cases = vectorsJson().getJSONArray("normalizeCases")
        assertTrue("normalize 用例太短，可能没读到文件", cases.length() >= 5)
        for (i in 0 until cases.length()) {
            val item = cases.getJSONObject(i)
            val input = if (item.isNull("input")) null else item.getString("input")
            assertEquals(item.getString("name"), item.getString("expected"), PinnedSsl.normalizeFingerprint(input))
        }
    }

    private fun vectorsJson(): JSONObject = JSONObject(vectorsFile().readText())

    /** 与 ContextInjectionTest.sharedCasesFile() 相同：从工作目录逐级上溯找仓库根 testdata/。 */
    private fun vectorsFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testdata/tls-fingerprint/vectors.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("找不到 testdata/tls-fingerprint/vectors.json")
    }
}
