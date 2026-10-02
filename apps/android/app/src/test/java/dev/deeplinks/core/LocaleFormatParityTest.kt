package dev.deeplinks.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 中英文案里的格式符必须按参数下标一一对应同一种类型（%s / %d / …）。
 * 出现顺序可以不同（例如英文把 %2$s 写在 %1$s 前面），类型不能不同。
 */
class LocaleFormatParityTest {
    @Test
    fun appLocaleFormatSpecifiersMatch() {
        val root = mainSourceRoot()
        val zh = scan(File(root, "dev/deeplinks/core/AppLocaleZh.kt"))
        val en = scan(File(root, "dev/deeplinks/core/AppLocaleEn.kt"))
        assertTrue("AppLocaleZh 没扫到词条", zh.isNotEmpty())
        assertTrue("AppLocaleEn 没扫到词条", en.isNotEmpty())

        val mismatches = mutableListOf<String>()
        for (key in (zh.keys + en.keys).toSortedSet()) {
            val zhTypes = zh[key]?.let { formatTypes(it) }
            val enTypes = en[key]?.let { formatTypes(it) }
            if (zhTypes == null || enTypes == null) continue
            if (zhTypes != enTypes) {
                mismatches += "$key zh=$zhTypes en=$enTypes"
            }
        }
        assertTrue(
            "中英文格式符类型不一致：\n" + mismatches.joinToString("\n"),
            mismatches.isEmpty(),
        )
    }

    private fun mainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "找不到 src/main/java" }
        return File(dir, "src/main/java")
    }

    private fun scan(file: File): Map<String, String> {
        val pairs = linkedMapOf<String, String>()
        file.forEachLine { line ->
            val match = PUT_PAIR.find(line) ?: return@forEachLine
            pairs[match.groupValues[1]] = match.groupValues[2]
        }
        return pairs
    }

    /**
     * 参数下标 → 转换符。%% 与 %n 不占参数。
     * 源码里的 \$ 先还原成 $，再按 java.util.Formatter 的规则编号。
     */
    private fun formatTypes(sourceValue: String): Map<Int, String> {
        val text = unescapeDollars(sourceValue)
        val types = sortedMapOf<Int, String>()
        var implicit = 0
        var previous = 0
        var i = 0
        while (i < text.length) {
            if (text[i] != '%') {
                i++
                continue
            }
            val match = SPEC.matchAt(text, i)
                ?: error("无法解析格式符：${text.substring(i)} ← $sourceValue")
            val explicit = match.groupValues[1].toIntOrNull()
            val flags = match.groupValues[2]
            val time = match.groupValues[3]
            val conv = match.groupValues[4]
            if (conv != "%" && conv != "n") {
                val type = if (time.isEmpty()) conv else time + conv
                val index = when {
                    flags.contains('<') -> previous
                    explicit != null -> explicit
                    else -> ++implicit
                }
                check(index > 0) { "格式符下标无效：$text" }
                val existing = types[index]
                check(existing == null || existing == type) {
                    "同一参数下标类型冲突：$text"
                }
                types[index] = type
                previous = index
            }
            i = match.range.last + 1
        }
        return types
    }

    private fun unescapeDollars(sourceValue: String): String = sourceValue.replace("\\$", "$")

    private companion object {
        val PUT_PAIR = Regex("put\\(\"([A-Za-z0-9_]+)\",\\s*\"(.*)\"\\s*\\)")
        val SPEC: Regex = Regex("""%(?:(\d+)\$)?([-#+ 0,(<]*)?(?:\d+)?(?:\.\d+)?([tT])?([a-zA-Z%])""")
    }
}
