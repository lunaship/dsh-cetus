package dev.deeplinks.architecture

import java.io.File

/**
 * 还没迁到 v4 的源文件，路径相对 `src/main/java`。
 *
 * 六个架构测试对白名单里的文件不做 v4 新规则检查。每迁完一个模块就删掉对应条目。
 * 阶段 4 这个集合必须为空，然后删除本文件。
 */
internal object V4MigrationAllowlist {
    val files: Set<String> = setOf(
        "dev/deeplinks/core/DshSyntaxPalette.kt",
        "dev/deeplinks/core/DswPalette.kt",
        "dev/deeplinks/devices/DeviceCard.kt",
        "dev/deeplinks/native/ApprovalCard.kt",
        "dev/deeplinks/native/ChatFeed.kt",
        "dev/deeplinks/native/ComposerBar.kt",
        "dev/deeplinks/native/DshMenu.kt",
        "dev/deeplinks/native/DshMotion.kt",
        "dev/deeplinks/native/DshSpace.kt",
        "dev/deeplinks/native/GoalCard.kt",
        "dev/deeplinks/native/HomeHub.kt",
        "dev/deeplinks/native/MarkdownContent.kt",
        "dev/deeplinks/native/MathRenderer.kt",
        "dev/deeplinks/native/MessageItem.kt",
        "dev/deeplinks/native/NewTaskDraftCanvas.kt",
        "dev/deeplinks/native/PlanChecklist.kt",
        "dev/deeplinks/native/ProducedFiles.kt",
        "dev/deeplinks/native/QuestionCard.kt",
        "dev/deeplinks/native/SessionControlUi.kt",
        "dev/deeplinks/native/SettingsRoute.kt",
        "dev/deeplinks/native/StreamingText.kt",
        "dev/deeplinks/native/ThinkingTrace.kt",
        "dev/deeplinks/native/TrajectoryView.kt",
        "dev/deeplinks/native/UsageSheet.kt",
        "dev/deeplinks/native/WorkspaceActivity.kt",
        "dev/deeplinks/native/WorkspaceChangesCard.kt",
        "dev/deeplinks/native/WorkspaceChangesPanel.kt",
        "dev/deeplinks/native/WorkspaceChrome.kt",
        "dev/deeplinks/native/WorkspaceSheets.kt",
        "dev/deeplinks/native/WorkspaceSidebar.kt",
        "dev/deeplinks/native/WorkspaceSidebarItems.kt",
        "dev/deeplinks/native/ui/DshAdvancedComponents.kt",
        "dev/deeplinks/native/ui/DshCardSurface.kt",
        "dev/deeplinks/native/ui/DshComponents.kt",
        "dev/deeplinks/native/ui/DshEmptyState.kt",
        "dev/deeplinks/native/ui/DshGroupedList.kt",
        "dev/deeplinks/native/ui/DshInbox.kt",
    )

    fun allows(relativePath: String): Boolean = relativePath in files

    fun mainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "找不到 src/main/java，user.dir=" + System.getProperty("user.dir") }
        return File(dir, "src/main/java")
    }

    fun relative(root: File, file: File): String =
        file.relativeTo(root).path.replace(File.separatorChar, '/')

    fun kotlinFiles(root: File): Sequence<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }

    /** 行注释和 KDoc 不参与门禁，避免说明文字里的类名被当成调用。 */
    fun codeLines(file: File): List<String> =
        file.readLines().filter { line ->
            val trimmed = line.trimStart()
            !trimmed.startsWith("//") && !trimmed.startsWith("*") && !trimmed.startsWith("/*")
        }
}
