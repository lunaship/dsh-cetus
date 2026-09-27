package dev.deeplinks.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 本地化目录对齐门禁（对照 lody-ios 的 i18n:check）。
 *
 * 中英文案必须是同一套 key：任一侧多出 / 缺失词条、空文案、同一文件里重复
 * 定义同一个 key（buildMap / mapOf 会静默覆盖）都算漂移。
 *
 * 覆盖两处目录：
 * - core/AppLocaleEn.kt 与 core/AppLocaleZh.kt（buildMap + put）；
 * - native/WorkspaceChangesText.kt 的 ChangesL（mapOf + "key" to value，
 *   以 `private val en = mapOf(` 为界分侧）。
 *
 * 只扫 key 与空值、不解析文案内容：值里允许转义引号，按 key 比对不受影响。
 * 目录迁址或重构词案形态时，本测试会因扫不到词条（低于下限）而失败——这是
 * 故意的：请先更新这里的扫描目标与下限，再改文案。
 */
class LocaleParityTest {

    private companion object {
        const val MAIN_CATALOG_MIN_KEYS = 500
        const val CHANGES_CATALOG_MIN_KEYS = 20

        /** put("key", "value")——捕获 key 与值；贪婪 .* 吞掉值内转义引号，锚点落在行尾的 ")。 */
        val PUT_PAIR = Regex("put\\(\"([A-Za-z0-9_]+)\",\\s*\"(.*)\"\\s*\\)")
        /** "key" to "value"——ChangesL 的词案行。 */
        val TO_PAIR = Regex("\"([A-Za-z0-9_]+)\"\\s+to\\s+\"(.*)\"")
    }

    private class Catalog(val name: String, val pairs: List<Pair<String, String>>) {
        val keys: List<String> get() = pairs.map { it.first }
        val blanks: List<String> get() = pairs.filter { it.second.isBlank() }.map { it.first }
        val duplicates: List<String>
            get() = keys.groupBy { it }.filter { it.value.size > 1 }.keys.sorted()
    }

    private fun mainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "找不到 src/main/java" }
        return File(dir, "src/main/java")
    }

    private fun scan(name: String, file: File, pair: Regex): Catalog {
        val pairs = mutableListOf<Pair<String, String>>()
        file.forEachLine { line ->
            pair.findAll(line).forEach { match ->
                pairs += match.groupValues[1] to match.groupValues[2]
            }
        }
        return Catalog(name, pairs)
    }

    private fun assertDriftFree(label: String, en: Catalog, zh: Catalog, minKeys: Int) {
        for (side in listOf(en, zh)) {
            assertTrue(
                "${side.name} 只扫到 ${side.keys.size} 个词条，低于下限 $minKeys——" +
                    "扫描模式可能已失效（文案换了写法或文件迁址），请先更新 LocaleParityTest",
                side.keys.size >= minKeys,
            )
            assertTrue(
                "${side.name} 有空文案：${side.blanks}",
                side.blanks.isEmpty(),
            )
            assertTrue(
                "${side.name} 有重复 key：${side.duplicates}",
                side.duplicates.isEmpty(),
            )
        }
        val onlyEn = en.keys.toSet() - zh.keys.toSet()
        val onlyZh = zh.keys.toSet() - en.keys.toSet()
        assertTrue(
            "$label 中英文案不同步：仅英文有 $onlyEn；仅中文有 $onlyZh",
            onlyEn.isEmpty() && onlyZh.isEmpty(),
        )
    }

    @Test
    fun appLocaleCatalogsStayInSync() {
        val root = mainSourceRoot()
        val en = scan("AppLocaleEn.kt", File(root, "dev/deeplinks/core/AppLocaleEn.kt"), PUT_PAIR)
        val zh = scan("AppLocaleZh.kt", File(root, "dev/deeplinks/core/AppLocaleZh.kt"), PUT_PAIR)
        assertDriftFree("AppLocale", en, zh, MAIN_CATALOG_MIN_KEYS)
    }

    @Test
    fun changesCopyCatalogsStayInSync() {
        val root = mainSourceRoot()
        val file = File(root, "dev/deeplinks/native/WorkspaceChangesText.kt")
        val text = file.readText()
        // 以 en 词案块的声明为界分侧；整文件混扫会把两侧搅在一起。
        val boundary = text.indexOf("private val en = mapOf(")
        assertTrue("WorkspaceChangesText.kt 找不到 en 词案块（`private val en = mapOf(`）", boundary > 0)
        fun scanSide(name: String, source: String): Catalog {
            val pairs = TO_PAIR.findAll(source)
                .map { it.groupValues[1] to it.groupValues[2] }
                .toList()
            return Catalog(name, pairs)
        }
        val zh = scanSide("ChangesL.zh", text.substring(0, boundary))
        val en = scanSide("ChangesL.en", text.substring(boundary))
        assertDriftFree("ChangesL", en, zh, CHANGES_CATALOG_MIN_KEYS)
    }
}
