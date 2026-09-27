package dev.deeplinks.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 组件语言门禁：守住「页面语法」边界，防止视觉重构后页面级重复实现重新长回来。
 *
 * 与 [DesignTokenUsageTest]（token 层：裸色值/裸字号/裸圆角）互补——本测试管
 * **组件与形状的用途**：pill 形状只能出现在共享组件或状态/筛选组件里，页面文件
 * 不得直接使用废弃的页面级形状语义（DshRadius.group），迁移期兼容包装
 * （DshLargeTitle / DshGroupedPage）和单页临时组件（HomeChip / DeviceTag）
 * 只有明确的清零预算。
 *
 * 预算语义（与 design-token-baseline 一致）：**只允许下调**。迁移使某项归零后，
 * 把对应预算改为 0（零容忍），不要再上调。合同见 docs/visual-rules.md。
 */
class ComponentLanguageTest {

    private companion object {
        /** 共享组件库：pill / 形状的新实现只允许落在这里。 */
        const val SHARED_UI = "dev/deeplinks/native/ui/"

        /** 兼容包装的定义处（自身引用不计入调用预算）。 */
        val DEFINITION_FILES = setOf(
            "dev/deeplinks/native/ui/DshGroupedList.kt",
        )

        /** 状态 / 筛选语义的 pill 合法宿主（会话状态、消息状态、轨迹状态、圆形动作键）。 */
        val STATUS_FILTER_FILES = setOf(
            "dev/deeplinks/native/WorkspaceSidebarItems.kt",
            "dev/deeplinks/native/ChatFeed.kt",
            "dev/deeplinks/native/TrajectoryView.kt",
            "dev/deeplinks/native/ComposerBar.kt",
        )

        /**
         * 页面文件直接使用 DshRadius.group 的存量。
         * 2026-09-27 批次 1：group 页面级语义已删除，WorkspaceChrome 的统计卡
         * 迁到 DshSection tonal（container）；预算归零，零容忍。
         */
        val GROUP_RADIUS_BUDGET: Map<String, Int> = emptyMap()

        /**
         * DshLargeTitle 调用点存量。
         * 2026-09-27 批次 3/6：设备页改用 DshPageScaffold 标准标题，兼容包装已删除；
         * 预算为零且组件不存在——任何出现（含定义）都失败。
         */
        val LARGE_TITLE_BUDGET: Map<String, Int> = emptyMap()

        /**
         * DshGroupedPage 兼容包装调用点存量（import 不计）。
         * 批次 6：设置内容改用 SettingsPageCanvas，兼容包装已删除，零容忍。
         */
        val GROUPED_PAGE_BUDGET: Map<String, Int> = emptyMap()

        /**
         * HomeChip 等单页临时组件调用点存量（定义行不计）。
         * 批次 3/4：DeviceTag → DshStatusBadge、HomeChip → DshFilterChip，
         * 预算全部归零，零容忍（PAGE_CHIP_BUDGET 为空即任何新出现都失败）。
         */
        val PAGE_CHIP_BUDGET: Map<String, Map<String, Int>> = emptyMap()

        /**
         * 其余页面文件的 pill 形状存量（逐批收敛到共享组件）。
         * 批次 5：composer 座位、上下文计量触发器、流式光标、统计触发器均已迁走；
         * 余下的是 M3 按钮惯例的实心/文字 CTA 与状态 pill（合同允许的场景）。
         */
        val PILL_SHAPE_BUDGET = mapOf(
            "dev/deeplinks/native/WorkspaceSheets.kt" to 1,
            "dev/deeplinks/native/ApprovalCard.kt" to 1,
            "dev/deeplinks/native/WorkspaceChangesPanel.kt" to 1,
            "dev/deeplinks/native/ProducedFiles.kt" to 1,
            "dev/deeplinks/native/WorkspaceChrome.kt" to 5,
            "dev/deeplinks/native/HomeHub.kt" to 3,
        )

        /** Settings / Devices 的 CircleShape 图标底板存量（批次 3 清零）。 */
        val CIRCLE_BACKDROP_BUDGET = mapOf(
            "dev/deeplinks/native/SettingsActivity.kt" to 0,
            "dev/deeplinks/devices/DevicesActivity.kt" to 0,
            "dev/deeplinks/devices/DeviceCard.kt" to 1,
        )

        val PILL_SHAPE = Regex("""RoundedCornerShape\(\s*DshRadius\.full""")
        val GROUP_RADIUS = Regex("""DshRadius\.group""")
        val CIRCLE_SHAPE = Regex("""CircleShape""")
        val DISPLAY_TYPE = Regex("""DshType\.display(Large)?""")
    }

    private fun mainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "找不到 src/main/java，user.dir=" + System.getProperty("user.dir") }
        return File(dir, "src/main/java")
    }

    private fun relative(root: File, file: File): String =
        file.relativeTo(root).path.replace(File.separatorChar, '/')

    private fun ktFiles(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun countMatches(file: File, regex: Regex, skipImports: Boolean = false): Int {
        var count = 0
        file.forEachLine { line ->
            val trimmed = line.trimStart()
            // import 与注释（KDoc 链接如 [DshGroupedPage]）不算调用
            if (skipImports && trimmed.startsWith("import ")) return@forEachLine
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachLine
            if (regex.containsMatchIn(line)) count++
        }
        return count
    }

    /** 页面文件 = 共享组件库之外的主源集文件。 */
    private fun isPageFile(rel: String): Boolean = !rel.startsWith(SHARED_UI)

    @Test
    fun pageFilesDoNotUseGroupRadius() {
        val root = mainSourceRoot()
        val violations = mutableListOf<String>()
        for (file in ktFiles(root)) {
            val rel = relative(root, file)
            if (!isPageFile(rel)) continue
            val used = countMatches(file, GROUP_RADIUS)
            val budget = GROUP_RADIUS_BUDGET[rel] ?: 0
            if (used > budget) {
                violations += "$rel: DshRadius.group $used 处，超过预算 $budget。" +
                    "20dp 分组卡语义已删除，请改用 DshSection(tonal = true)（container 12dp）"
            }
        }
        assertTrue(
            "页面级形状语义残留（docs/visual-rules.md 第三节）：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun largeTitleCallsOnlyShrink() {
        val root = mainSourceRoot()
        val violations = mutableListOf<String>()
        for (file in ktFiles(root)) {
            val rel = relative(root, file)
            if (rel in DEFINITION_FILES) continue
            val used = countMatches(file, Regex("""DshLargeTitle"""), skipImports = true)
            val budget = LARGE_TITLE_BUDGET[rel] ?: 0
            if (used > budget) {
                violations += "$rel: DshLargeTitle $used 处，超过预算 $budget。" +
                    "页面标题统一走 DshPageScaffold（headlineMedium）"
            }
        }
        assertTrue(
            "DshLargeTitle 调用只减不增（docs/visual-rules.md 第四节）：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun groupedPageWrapperCallsOnlyShrink() {
        val root = mainSourceRoot()
        val violations = mutableListOf<String>()
        for (file in ktFiles(root)) {
            val rel = relative(root, file)
            if (rel in DEFINITION_FILES) continue
            val used = countMatches(file, Regex("""DshGroupedPage"""), skipImports = true)
            val budget = GROUPED_PAGE_BUDGET[rel] ?: 0
            if (used > budget) {
                violations += "$rel: DshGroupedPage $used 处，超过预算 $budget。" +
                    "兼容包装只减不增，请改到 DshPageScaffold / DshSection"
            }
        }
        assertTrue(
            "DshGroupedPage 调用只减不增：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun temporaryPageComponentsHaveZeroBudget() {
        val root = mainSourceRoot()
        val violations = mutableListOf<String>()
        for (file in ktFiles(root)) {
            val rel = relative(root, file)
            val chips = PAGE_CHIP_BUDGET[rel] ?: continue
            for ((name, budget) in chips) {
                // 调用点：出现 "Name(" 但不是定义行（定义行以 fun 开头）
                var used = 0
                val definition = Regex("""fun\s+$name\s*\(""")
                file.forEachLine { line ->
                    if (definition.containsMatchIn(line)) return@forEachLine
                    if (Regex("""\b$name\s*\(""").containsMatchIn(line)) used++
                }
                if (used > budget) {
                    violations += "$rel: $name $used 处，超过预算 $budget。" +
                        "单页临时组件必须换成共享组件（如 DshFilterChip），新页面不得新增"
                }
            }
        }
        // 未登记文件出现这些名字 = 新增单页视觉形状组件，直接失败（注释里的迁移说明不算）
        val banned = setOf("DeviceTag", "HomeChip")
        for (file in ktFiles(root)) {
            val rel = relative(root, file)
            if (rel in DEFINITION_FILES || rel in PAGE_CHIP_BUDGET) continue
            for (name in banned) {
                val code = file.readLines()
                    .filterNot { line ->
                        val t = line.trimStart()
                        t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
                    }
                    .joinToString("\n")
                if (Regex("""\b$name\b""").containsMatchIn(code)) {
                    violations += "$rel: 出现 $name——单页视觉形状命名组件，必须改用共享组件（DshStatusBadge / DshFilterChip）"
                }
            }
        }
        assertTrue(
            "单页临时组件预算（docs/visual-rules.md 第五节）：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun pillShapeStaysInSharedOrStatusComponents() {
        val root = mainSourceRoot()
        val violations = mutableListOf<String>()
        for (file in ktFiles(root)) {
            val rel = relative(root, file)
            if (rel.startsWith(SHARED_UI) || rel in STATUS_FILTER_FILES) continue
            val used = countMatches(file, PILL_SHAPE)
            val budget = PILL_SHAPE_BUDGET[rel] ?: 0
            if (used > budget) {
                violations += "$rel: RoundedCornerShape(DshRadius.full) $used 处，超过预算 $budget。" +
                    "pill 只允许共享组件或状态/筛选组件使用；新需求请先加共享组件"
            }
        }
        assertTrue(
            "pill 形状越界（docs/visual-rules.md 第三节）：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun settingsAndDevicesDoNotAddCircleBackdrops() {
        val root = mainSourceRoot()
        val violations = mutableListOf<String>()
        for ((rel, budget) in CIRCLE_BACKDROP_BUDGET) {
            val file = File(root, rel)
            if (!file.isFile) continue
            val used = countMatches(file, CIRCLE_SHAPE, skipImports = true)
            if (used > budget) {
                violations += "$rel: CircleShape $used 处，超过预算 $budget。" +
                    "设置/设备页不得新增图标底板；品牌块只在未配对空态保留"
            }
        }
        assertTrue(
            "CircleShape 图标底板（docs/visual-rules.md 第三节）：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun pageTitlesDoNotUseDisplayScale() {
        val root = mainSourceRoot()
        val violations = mutableListOf<String>()
        for (file in ktFiles(root)) {
            val rel = relative(root, file)
            if (!isPageFile(rel)) continue
            if (DISPLAY_TYPE.containsMatchIn(file.readText())) {
                violations += "$rel: 页面文件使用了 DshType.display/displayLarge——页面标题统一 headlineMedium"
            }
        }
        assertTrue(
            "页面标题字阶（docs/visual-rules.md 第四节）：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }
}
