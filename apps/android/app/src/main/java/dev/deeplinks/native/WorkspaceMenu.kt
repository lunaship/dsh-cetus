package dev.deeplinks.native

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.L
import dev.deeplinks.core.previewTitle
import dev.deeplinks.core.scheduledTasks
import dev.deeplinks.native.ui.v4.DlBottomSheet
import dev.deeplinks.native.ui.v4.DlListRow
import dev.deeplinks.native.ui.v4.DlRowTrailing
import dev.deeplinks.native.ui.v4.DlSectionHeader

/** 会话菜单文案（词条在 AppLocaleZh / En）。 */
internal object MenuL {
    val menuGroupView: String get() = L.translation("menuGroupView")
    val menuGroupActions: String get() = L.translation("menuGroupActions")
    val menuChanges: String get() = L.translation("menuChanges")
    val menuFiles: String get() = L.translation("menuFiles")
    val menuFilesSubtitle: String get() = L.translation("menuFilesSubtitle")
    val menuTrace: String get() = L.translation("menuTrace")
    val menuRename: String get() = L.translation("menuRename")
    val menuFork: String get() = L.translation("menuFork")
    val menuShare: String get() = L.translation("menuShare")
    val menuChangesSubtitle: String get() = L.translation("menuChangesSubtitle")
    val menuSubagentsRunning: String get() = L.translation("menuSubagentsRunning")
}

/** 会话 ⋯ 菜单的一项：图标 + 名称 + 可选副标题。 */
internal data class SessionMenuEntry(
    val icon: ImageVector,
    val label: String,
    val subtitle: String? = null,
    val onClick: () -> Unit,
)

/** 会话 ⋯ 菜单（v4 4.9）：上组「查看」，下组「操作」。归档 / 删除只在首页长按菜单。 */
internal data class SessionMenu(val view: List<SessionMenuEntry>, val actions: List<SessionMenuEntry>) {
    val isEmpty: Boolean get() = view.isEmpty() && actions.isEmpty()

    companion object {
        val Empty = SessionMenu(emptyList(), emptyList())
    }
}

/**
 * 纯列表构建：只接收状态与回调，不做 IO。每一项点按先关菜单再执行。
 * 查看：改动（有改动时）/ 文件（插件支持文件树）/ 轨迹 / 子代理（有运行中）/ 用量 / 预览（支持时）。
 * 操作：目标（有可管理目标）/ 定时任务（插件支持会话控制）/ 重命名 / 分叉 / 分享。
 */
internal fun sessionMenu(
    onClose: () -> Unit,
    changes: WorkspaceChangesSummary? = null,
    onChanges: () -> Unit = {},
    canBrowseFiles: Boolean = false,
    onBrowseFiles: () -> Unit = {},
    viewMode: String = "chat",
    onToggleViewMode: () -> Unit = {},
    subagentCount: Int = 0,
    onSubagents: () -> Unit = {},
    onUsage: () -> Unit = {},
    previewSupported: Boolean = false,
    onPreview: () -> Unit = {},
    canGoal: Boolean = false,
    onGoal: () -> Unit = {},
    canSchedules: Boolean = false,
    onSchedules: () -> Unit = {},
    onRename: () -> Unit = {},
    onFork: () -> Unit = {},
    onShare: () -> Unit = {},
): SessionMenu {
    fun entry(icon: ImageVector, label: String, subtitle: String? = null, action: () -> Unit) =
        SessionMenuEntry(icon, label, subtitle) { onClose(); action() }
    val view = buildList {
        if (changes != null && changes.total > 0) {
            add(entry(FileOutline16, MenuL.menuChanges, MenuL.menuChangesSubtitle.format(changes.total, changes.added, changes.deleted), onChanges))
        }
        if (canBrowseFiles) add(entry(FolderOpenOutline16, MenuL.menuFiles, MenuL.menuFilesSubtitle, onBrowseFiles))
        add(entry(ListPenOutline16, dev.deeplinks.native.util.viewModeToggleLabel(viewMode, MenuL.menuTrace, L.showChat), action = onToggleViewMode))
        if (subagentCount > 0) add(entry(AgentPresetOutline16, L.subagents, MenuL.menuSubagentsRunning.format(subagentCount), onSubagents))
        add(entry(DataOutline16, L.translation("usageOpen"), action = onUsage))
        if (previewSupported) add(entry(GlobeOutline16, L.previewTitle, action = onPreview))
    }
    val actions = buildList {
        if (canGoal) add(entry(GoalOutline16, L.goalRole, action = onGoal))
        if (canSchedules) add(entry(ClockOutline16, L.scheduledTasks, action = onSchedules))
        add(entry(EditOutline16, MenuL.menuRename, action = onRename))
        add(entry(BranchOutline16, MenuL.menuFork, action = onFork))
        add(entry(ShareOutline16, MenuL.menuShare, action = onShare))
    }
    return SessionMenu(view, actions)
}

/** ⋯ 菜单弹层：两组平铺列表，组间 1dp 分隔线。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SessionMenuSheet(menu: SessionMenu, onDismiss: () -> Unit) {
    DlBottomSheet(onDismissRequest = onDismiss) { SessionMenuContent(menu) }
}

@Composable
internal fun SessionMenuContent(menu: SessionMenu) {
    if (menu.view.isNotEmpty()) {
        DlSectionHeader(MenuL.menuGroupView)
        for (item in menu.view) {
            DlListRow(item.label, subtitle = item.subtitle, leading = item.icon, trailing = DlRowTrailing.Chevron, onClick = item.onClick)
        }
    }
    if (menu.view.isNotEmpty() && menu.actions.isNotEmpty()) HorizontalDivider(thickness = 1.dp, color = Dsh.outline)
    for (item in menu.actions) {
        DlListRow(item.label, subtitle = item.subtitle, leading = item.icon, onClick = item.onClick)
    }
}
