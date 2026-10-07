package dev.deeplinks.core

import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * 测试用的自签 TLS 服务端身份。
 *
 * 这些用例以前依赖仓库里提交的 `dlp1-test-server.p12`。那是私钥文件：虽然只用于测试
 * （`CN=localhost`、口令写在测试里），私钥仍不该进版本库，也会干扰密钥扫描与轮换。
 * 现在改为首次使用时用 `keytool` 在临时目录现生成一份自签身份（生成后立即加载进内存并
 * 删除临时文件），测试依旧走真实的 TLS 服务端 socket，覆盖范围不变。
 */
object TestServerIdentity {

    /** 生成一份自签身份并装进 PKCS12 KeyStore（进程内只生成一次）。 */
    fun keyStore(): KeyStore = CACHE

    /** 证书 DER 的 SHA-256，小写十六进制、无冒号，与 PinnedSsl 同规则。 */
    fun pin(keyStore: KeyStore): String {
        val der = certificate(keyStore).encoded
        return MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }
    }

    fun certificate(keyStore: KeyStore): X509Certificate =
        keyStore.getCertificate(keyStore.aliases().nextElement()) as X509Certificate

    /** 按 KeyStore 建服务端 SSLContext，供 MockWebServer 与裸 SSLServerSocket 使用。 */
    fun serverContext(keyStore: KeyStore): SSLContext {
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, PASSWORD) }
        return SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
    }

    private val PASSWORD = "dlp1-test".toCharArray()
    private const val ALIAS = "dlp1-test-server"

    private val CACHE: KeyStore by lazy { generate() }

    private fun generate(): KeyStore {
        val dir = Files.createTempDirectory("dlp1-test-identity").toFile()
        try {
            val p12 = File(dir, "server.p12")
            keytool(
                "-genkeypair",
                "-alias", ALIAS,
                "-keyalg", "EC",
                "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA",
                "-dname", "CN=localhost",
                "-validity", "3650",
                "-storetype", "PKCS12",
                "-keystore", p12.absolutePath,
                "-storepass", String(PASSWORD),
                "-keypass", String(PASSWORD),
            )
            return KeyStore.getInstance("PKCS12").apply { load(p12.inputStream(), PASSWORD) }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun keytool(vararg args: String) {
        val process = ProcessBuilder(keytoolPath(), *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("keytool 超时：${args.joinToString(" ")}")
        }
        if (process.exitValue() != 0) {
            error("keytool 失败（exit=${process.exitValue()}）：$output")
        }
    }

    private fun keytoolPath(): String =
        File(System.getProperty("java.home"), "bin/keytool").absolutePath
}
