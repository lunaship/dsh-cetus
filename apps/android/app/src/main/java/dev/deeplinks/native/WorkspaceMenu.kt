package dev.deeplinks.native

import dev.deeplinks.core.L

/**
 * 顶栏「更多操作」菜单项（从 WorkspaceScreen 抽出，COM-001 拆解）。
 *
 * 纯列表构建：只接收状态与回调，不持有业务逻辑、不做 IO。
 * 2026-09-30 C1 精简：最多 5 项——重命名 / 浏览文件（按能力）/ 分享 / 归档 / 删除。
 * 工具查找、跳转轮次、复制标题、设备入口、分叉都从溢出菜单移除（子智能体入口移入溢出菜单第一项）。
 */
internal fun workspaceHeaderMenuItems(
    canBrowseFiles: Boolean,
    onCloseMenu: () -> Unit,
    onBrowseFiles: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
): List<DshMenuItem> = buildList {
    add(DshMenuItem(EditOutline16, L.renameSession) { onCloseMenu(); onRename() })
    if (canBrowseFiles) {
        add(DshMenuItem(FolderOpenOutline16, L.browseFiles) { onCloseMenu(); onBrowseFiles() })
    }
    add(DshMenuItem(ShareOutline16, L.shareConversation) { onCloseMenu(); onShare() })
    add(DshMenuItem(ArchiveOutline20, L.archiveSession) { onCloseMenu(); onArchive() })
    // 危险操作放最底一行，前面加分隔线
    add(DshMenuItem(TrashOutline16, L.deleteSession, danger = true, dividerBefore = true) { onCloseMenu(); onDelete() })
}
