package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** release 明文只放行预览用的 127.0.0.1，防止以后被放宽成全局明文。 */
class NetworkSecurityConfigTest {

    @Test
    fun `release cleartext is only the loopback address`() {
        val doc = parse(configFile("src/main/res/xml/network_security_config.xml"))
        val bases = doc.getElementsByTagName("base-config")
        assertEquals(1, bases.length)
        assertEquals("false", (bases.item(0) as Element).getAttribute("cleartextTrafficPermitted"))

        val configs = doc.getElementsByTagName("domain-config")
        assertEquals(1, configs.length)
        val config = configs.item(0) as Element
        assertEquals("true", config.getAttribute("cleartextTrafficPermitted"))

        val domains = config.getElementsByTagName("domain")
        assertEquals(1, domains.length)
        val domain = domains.item(0) as Element
        assertEquals("127.0.0.1", domain.textContent.trim())
        assertEquals("false", domain.getAttribute("includeSubdomains"))
        assertTrue(doc.getElementsByTagName("domain").length == 1)
    }

    @Test
    fun `debug override still permits the loopback address`() {
        val doc = parse(configFile("src/debug/res/xml/network_security_config.xml"))
        val domains = doc.getElementsByTagName("domain")
        val loopback = (0 until domains.length)
            .map { domains.item(it) as Element }
            .filter { it.textContent.trim() == "127.0.0.1" }
        assertEquals(1, loopback.size)
        assertEquals("false", loopback.single().getAttribute("includeSubdomains"))
    }

    @Test
    fun `host traffic still rejects cleartext http`() {
        val text = configFile("src/main/java/dev/deeplinks/core/HostHttp.kt").readText()
        assertEquals(2, Regex("""require\(url\.startsWith\("https://"\)\) \{ "拒绝明文 HTTP，仅支持 HTTPS" \}""").findAll(text).count())
    }

    private fun parse(file: File) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    private fun configFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        return File(requireNotNull(dir) { "找不到 Android app 模块" }, relative)
    }
}
