package dev.deeplinks.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 容器分层门禁：卡片/容器不得用 1dp `borderSubtle` 描边（Web 卡片思维）。
 *
 * 原生 M3 的层级语言是 **tonal 色阶 + 阴影**：容器彼此靠 `bgBase → bgCard →
 * bgSubtle → bgTrack` 的明度差分开，弹层靠阴影浮起。1dp 发丝线描边是网页
 * 卡片的做法——在浅色主题下白底白卡只剩一根灰线，正是「像网页套壳」的
 * 来源之一。2026-09 已把设置分组卡、设备卡、审批/问题卡、模型选择行、
 * 菜单浮层全部改为 tonal 填充；本测试锁死该方向。
 *
 * 允许的例外（[ALLOWLIST]）只放**功能性**描边：媒体取景框（图片预览需要
 * 边框界定内容边界）。选择控件（radio 环）、代码块横幅细线、输入框
 * outline 不使用 `borderSubtle` 字面量，天然不受本测试约束。
 */
class SurfaceHierarchyTest {

    private companion object {
        /** 功能性描边白名单：文件 → 原因。 */
        val ALLOWLIST = mapOf(
            "dev/deeplinks/native/ImageCropSheet.kt" to "媒体取景框：边框界定图片内容边界",
        )
        val HAIRLINE_CARD_BORDER = Regex(
            """\.border\(1\.dp,\s*Dsh\.borderSubtle|BorderStroke\(1\.dp,\s*Dsh\.borderSubtle""",
        )
    }

    private fun mainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "找不到 src/main/java" }
        return File(dir, "src/main/java")
    }

    private fun relative(root: File, file: File): String =
        file.relativeTo(root).path.replace(File.separatorChar, '/')

    @Test
    fun containersUseTonalSurfacesNotHairlineBorders() {
        val root = mainSourceRoot()
        val violations = mutableListOf<String>()
        var scanned = 0

        for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val rel = relative(root, file)
            if (rel in ALLOWLIST) continue
            scanned++
            file.forEachLine { line ->
                if (HAIRLINE_CARD_BORDER.containsMatchIn(line)) {
                    violations += "$rel: ${line.trim()}——容器请改用 tonal 填充（Dsh.bgSubtle/bgCard），弹层用阴影"
                }
            }
        }

        assertTrue("一个文件都没扫到，扫描模式可能已失效", scanned > 50)
        assertTrue(
            "容器描边违规（Web 卡片思维）：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun allowlistStaysMinimal() {
        // 白名单只允许功能性描边；条目增长说明又在用描边画容器了。
        assertTrue(
            "描边白名单只放功能性场景（当前 ${ALLOWLIST.size} 条），不要往里面加容器卡片",
            ALLOWLIST.size <= 2,
        )
    }
}
