package dev.deeplinks.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 字阶契约门禁：DshType 只允许两种角色——
 *
 * 1. **语义角色**：委托 `MaterialTheme.typography.*`（可 `.copy(fontWeight=...)`），
 *    即 M3 字阶本体，禁止自造尺寸；
 * 2. **密集档角色**（[DENSE_ROLES] 白名单）：聊天/列表的次级文本，内联 TextStyle，
 *    但必须满足：
 *    - 字号落在 M3 字阶表 [SCALE] 内；
 *    - 行高/字号 ∈ [MIN_RATIO, MAX_RATIO]——上限 1.65 即「不得比 M3 最松的
 *      bodyLarge（16/26 ≈ 1.63）更松」。Web 移植期的「小字号 + 松行高」正文
 *      （t13 = 13/22、t12 = 12/22、t11 = 11/22）全部超过该上限，已被淘汰；
 *    - letterSpacing 只允许 [TRACKING] 内的取值。
 *
 * 背景：App 早期 1:1 平移 DSH Web 的 CSS 像素字号，长出 40 多个 `t11x12SB` 式的
 * 微角色、286 处调用点——这是「像网页套壳」的最大单一来源。2026-09 重构把
 * 13sp 正文族与超松行高角色并入语义角色（body/bodyStrong 15sp 起）；
 * 本测试把成果锁死：违反契约的角色会红，白名单外的角色名也会红（防止静默回流）。
 */
class DshTypeScaleTest {

    private companion object {
        /** M3 字阶表（sp）。 */
        val SCALE = setOf(11, 12, 13, 14, 15, 16, 17, 18, 20, 24, 28, 34)
        const val MIN_RATIO = 1.2

        /** 行高/字号上限：M3 最松的 bodyLarge 是 16/26 ≈ 1.63，取 1.65 作护栏。 */
        const val MAX_RATIO = 1.65
        val TRACKING = setOf(0f, 0.01f, 0.02f, 0.03f, 0.04f)

        /** 语义角色：必须委托 M3 字阶。 */
        val SEMANTIC_ROLES = setOf(
            "caption", "label", "titleSmall", "body", "bodyStrong", "title",
            "bodyLarge", "titleLarge", "headline", "headlineMedium",
            "display", "displayLarge", "labelLarge",
        )

        /** 密集档白名单：次级文本专用，尺寸/行高受上面的规则约束。 */
        val DENSE_ROLES = setOf(
            "captionRelaxed", "microRelaxed", "microMedium", "microStrong",
            "t11x15M", "t11x16M", "t11x17", "t11x18",
            "t12x18M",
            "t14", "t14M", "t14SB", "t14x20",
            "t15x20", "t15x20M", "t15x21M", "t15x21SB", "t15x23",
        )
    }

    private data class Role(
        val name: String,
        val body: String,
        val fontSize: Int?,
        val lineHeight: Int?,
        val letterSpacing: Float?,
    ) {
        val inline: Boolean get() = fontSize != null
    }

    private fun mainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "找不到 src/main/java" }
        return File(dir, "src/main/java")
    }

    private fun parseRoles(file: File): List<Role> {
        val text = file.readText()
        val start = text.indexOf("object DshType {")
        assertTrue("DshTypography.kt 找不到 object DshType", start >= 0)
        val block = text.substring(start)
        val headers = Regex("""val (\w+): TextStyle""").findAll(block).toList()
        return headers.mapIndexed { index, match ->
            val from = match.range.last + 1
            val to = headers.getOrNull(index + 1)?.range?.first ?: block.length
            val body = block.substring(from, to)
            fun int(name: String) =
                Regex("""$name = (\d+(?:\.\d+)?)\.sp""").find(body)?.groupValues?.get(1)?.toInt()
            Role(
                name = match.groupValues[1],
                body = body,
                fontSize = int("fontSize"),
                lineHeight = int("lineHeight"),
                letterSpacing = Regex("""letterSpacing = (-?[\d.]+)\.sp""").find(body)
                    ?.groupValues?.get(1)?.toFloatOrNull(),
            )
        }
    }

    @Test
    fun rolesStayOnTheNativeScale() {
        val roles = parseRoles(File(mainSourceRoot(), "dev/deeplinks/core/DshTypography.kt"))
        assertTrue("DshType 一个角色都没解析到，扫描模式可能已失效", roles.size >= 30)
        val violations = mutableListOf<String>()

        for (role in roles) {
            val known = role.name in SEMANTIC_ROLES || role.name in DENSE_ROLES
            if (!known) {
                violations += "${role.name}: 不在白名单（语义 ${SEMANTIC_ROLES.size} + 密集 ${DENSE_ROLES.size}）。" +
                    "新角色请先想清楚属于哪一档，并更新 DshTypeScaleTest"
                continue
            }
            if (!role.inline) {
                // 语义角色：只允许委托 M3 字阶（可 copy 改字重），禁止自造尺寸。
                if (!Regex("""MaterialTheme\.typography\.\w+(\.copy\([^)]*\))?""").containsMatchIn(role.body)) {
                    violations += "${role.name}: 语义角色必须委托 MaterialTheme.typography.*，实际：${role.body.trim().take(60)}"
                }
                continue
            }
            val fs = role.fontSize!!
            val lh = role.lineHeight
            if (fs !in SCALE) violations += "${role.name}: 字号 ${fs}sp 不在 M3 字阶表 $SCALE"
            if (lh == null) {
                violations += "${role.name}: 密集档角色必须显式声明 lineHeight"
            } else {
                val ratio = lh.toDouble() / fs
                if (ratio < MIN_RATIO || ratio > MAX_RATIO) {
                    violations += "${role.name}: 行高/字号 = %.2f，超出 [%.1f, %.1f]（$fs/${lh}）。".format(ratio, MIN_RATIO, MAX_RATIO) +
                        "比 M3 bodyLarge（16/26）还松的行高是 Web 移植期形态；正文本请用 body/bodyStrong（15sp 起）"
                }
            }
            val ls = role.letterSpacing
            if (ls != null && ls !in TRACKING) {
                violations += "${role.name}: letterSpacing $ls 不在允许集 $TRACKING"
            }
        }

        assertTrue("字阶契约违规：\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun semanticAndDenseTiersCoverEveryRole() {
        val roles = parseRoles(File(mainSourceRoot(), "dev/deeplinks/core/DshTypography.kt"))
        val names = roles.map { it.name }.toSet()
        val expected = SEMANTIC_ROLES + DENSE_ROLES
        val missing = expected - names
        val extra = names - expected
        assertTrue(
            "DshType 角色面与契约不符：缺失 $missing；多出 $extra（白名单需与实现同步）",
            missing.isEmpty() && extra.isEmpty(),
        )
    }
}
