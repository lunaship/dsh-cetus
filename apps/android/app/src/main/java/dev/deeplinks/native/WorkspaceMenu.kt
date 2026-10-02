package dev.deeplinks.native

import dev.deeplinks.core.L
import dev.deeplinks.core.scheduledTasks

/**
 * 顶栏「更多操作」菜单项（从 WorkspaceScreen 抽出，COM-001 拆解）。
 *
 * 纯列表构建：只接收状态与回调，不持有业务逻辑、不做 IO。
 * 2026-09-30 C1 精简后加上用量：重命名 / 用量 / 浏览文件（按能力）/ 分享 / 归档 / 删除。
 * 工具查找、跳转轮次、复制标题、设备入口、分叉都从溢出菜单移除（子智能体入口移入溢出菜单第一项）。
 * 插件支持会话控制（capabilities.control）时多一项「定时任务」。
 */
internal fun workspaceHeaderMenuItems(
    canBrowseFiles: Boolean,
    onCloseMenu: () -> Unit,
    onBrowseFiles: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onUsage: () -> Unit = {},
    onArchive: () -> Unit,
    canSchedules: Boolean = false,
    onSchedules: () -> Unit = {},
    onDelete: () -> Unit,
): List<DshMenuItem> = buildList {
    add(DshMenuItem(EditOutline16, L.renameSession) { onCloseMenu(); onRename() })
    add(DshMenuItem(DataOutline16, L.translation("usageOpen")) { onCloseMenu(); onUsage() })
    if (canBrowseFiles) {
        add(DshMenuItem(FolderOpenOutline16, L.browseFiles) { onCloseMenu(); onBrowseFiles() })
    }
    add(DshMenuItem(ShareOutline16, L.shareConversation) { onCloseMenu(); onShare() })
    if (canSchedules) add(DshMenuItem(ChecklistOutline16, L.scheduledTasks) { onCloseMenu(); onSchedules() })
    add(DshMenuItem(ArchiveOutline20, L.archiveSession) { onCloseMenu(); onArchive() })
    // 危险操作放最底一行，前面加分隔线
    add(DshMenuItem(TrashOutline16, L.deleteSession, danger = true, dividerBefore = true) { onCloseMenu(); onDelete() })
}

/**
 * 顶栏「⋯」菜单（2026-10-02 L6）：首项 = 查看轨迹 / 返回对话（文字随当前视图切换），
 * 有子智能体时第二项是子智能体入口（小圆点提示在按钮上），其后是既有条目。
 */
internal fun buildTopBarMenu(
    viewMode: String,
    topBarMenuItems: List<DshMenuItem>,
    activeSubagentCount: Int,
    onToggleViewMode: () -> Unit,
    onOpenSubagents: () -> Unit,
): List<DshMenuItem> = buildList {
    add(
        DshMenuItem(
            icon = ListPenOutline16,
            label = dev.deeplinks.native.util.viewModeToggleLabel(viewMode, L.viewInTrace, L.showChat),
            onClick = onToggleViewMode,
        ),
    )
    if (activeSubagentCount > 0) {
        add(
            DshMenuItem(
                icon = AgentPresetOutline16,
                label = L.subagentCount.format(activeSubagentCount),
                onClick = onOpenSubagents,
            ),
        )
    }
    addAll(topBarMenuItems)
}
