package dev.deeplinks.native

import dev.deeplinks.core.L

/**
 * 顶栏「更多操作」菜单项（从 WorkspaceScreen 抽出，COM-001 拆解）。
 *
 * 纯列表构建：只接收状态与回调，不持有业务逻辑、不做 IO；
 * 具体动作（重命名/分叉/分享图/导出/归档/设备/删除）由调用方以 lambda 注入。
 */
internal fun workspaceHeaderMenuItems(
    viewMode: String,
    toolSearchOpen: Boolean,
    activeSubagentCount: Int,
    turnJumpCount: Int,
    canBrowseFiles: Boolean,
    onCloseMenu: () -> Unit,
    onOpenToolSearch: () -> Unit,
    onShowSubagents: () -> Unit,
    onShowTurnJump: () -> Unit,
    onBrowseFiles: () -> Unit,
    onRename: () -> Unit,
    onFork: () -> Unit,
    onCopyTitle: () -> Unit,
    onArchive: () -> Unit,
    onShareImage: () -> Unit,
    onExport: () -> Unit,
    onOpenDevice: () -> Unit,
    onDelete: () -> Unit,
): List<DshMenuItem> {
    val contextual = buildList {
        if (viewMode == "chat") {
            add(DshMenuItem(SearchOutline16, if (toolSearchOpen) L.closeToolSearch else L.searchToolCalls) {
                onCloseMenu(); onOpenToolSearch()
            })
        }
        if (activeSubagentCount > 0) {
            add(DshMenuItem(AgentPresetOutline16, L.subagentCount.format(activeSubagentCount)) {
                onCloseMenu(); onShowSubagents()
            })
        }
        if (canBrowseFiles) {
            add(DshMenuItem(FolderOpenOutline16, L.browseFiles) { onCloseMenu(); onBrowseFiles() })
        }
        if (turnJumpCount >= 3) {
            add(DshMenuItem(ChecklistOutline14, L.jumpToTurn) {
                onCloseMenu(); onShowTurnJump()
            })
        }
    }
    return contextual + listOf(
        DshMenuItem(EditOutline16, L.renameSession) { onCloseMenu(); onRename() },
        DshMenuItem(BranchOutline16, L.forkSession) { onCloseMenu(); onFork() },
        DshMenuItem(CopyOutline16, L.copySessionTitle) { onCloseMenu(); onCopyTitle() },
        DshMenuItem(ArchiveOutline20, L.archiveSession) { onCloseMenu(); onArchive() },
        DshMenuItem(ImageOutline16, L.shareConversationImage) { onCloseMenu(); onShareImage() },
        DshMenuItem(ShareOutline16, L.exportConversation) { onCloseMenu(); onExport() },
        DshMenuItem(DevicesOutline16, L.deviceAndPairing) { onCloseMenu(); onOpenDevice() },
        DshMenuItem(TrashOutline16, L.deleteSession, danger = true) { onCloseMenu(); onDelete() },
    )
}
