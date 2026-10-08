package dev.deeplinks.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 方案 §18「更名」：用户界面统一叫 cetus。
 *
 * app-rebrand 把产品名从 DeepLinks / dsh-links 换成 cetus，但**安装身份**
 * （`applicationId = dev.deeplinks`）刻意保留，否则升级会丢数据（§18 表格：
 * 「安装身份可保留 legacy」）。所以这里只守**用户可见文案**，不扫 `package` 行、
 * 不扫 `applicationId`。
 *
 * 之所以要它的原因：改名是跨几十个文件的机械替换，落下一两处不会让任何测试变红，
 * 却会让用户看到旧名字（例如「电脑端 dsh-links 插件版本过旧」）。这条测试把口径钉住。
 */
class ProductNamingTest {

    /** 旧名字出现在用户可见文案里即为回归。安装身份 `dev.deeplinks` 不在此列。 */
    private val staleNames = listOf("dsh-links", "DeepLinks", "deeplinks")

    private fun mainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "找不到 src/main/java" }
        return File(dir, "src/main/java")
    }

    @Test
    fun userVisibleCopyDoesNotUseTheOldProductName() {
        val root = mainSourceRoot()
        val offenders = mutableListOf<String>()
        for (name in listOf("AppLocaleEn.kt", "AppLocaleZh.kt")) {
            val file = File(root, "dev/deeplinks/core/$name")
            file.forEachLine { line ->
                // 只检查 put("key", "文案") 里的文案部分；package / import / 注释不参与。
                val value = PUT_PAIR.find(line)?.groupValues?.get(2) ?: return@forEachLine
                // 电脑上的工作目录可能叫 dsh-links（用户真实路径），那是数据不是产品名。
                if (value.contains("/Volumes/") || value.contains("Users/")) return@forEachLine
                for (stale in staleNames) {
                    if (value.contains(stale, ignoreCase = true)) {
                        offenders += "$name: $stale → $value"
                    }
                }
            }
        }
        assertTrue(
            "用户可见文案里还有旧产品名（改名前叫 dsh-links / DeepLinks）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun installIdentityStaysLegacySoUpgradesKeepData() {
        // §18：「安装身份可保留 legacy」。改成新 id 会让老用户装成第二个 App、丢数据。
        val buildScript = findBuildScript()
        val text = buildScript.readText()
        assertTrue(
            "applicationId 必须保持 dev.deeplinks",
            text.contains("""applicationId = "dev.deeplinks""""),
        )
        assertTrue("namespace 必须保持 dev.deeplinks", text.contains("""namespace = "dev.deeplinks""""))
    }

    private fun findBuildScript(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/build.gradle.kts").isFile) dir = dir.parentFile
        requireNotNull(dir) { "找不到 app/build.gradle.kts" }
        return File(dir, "app/build.gradle.kts")
    }

    private companion object {
        val PUT_PAIR = Regex("""put\("([^"]+)",\s*"((?:[^"\\]|\\.)*)"""")
    }
}
