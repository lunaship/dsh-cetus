package dev.deeplinks.native

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.homeComputerViaLan
import dev.deeplinks.core.homeComputerViaRelay
import dev.deeplinks.core.homeMore
import dev.deeplinks.core.homeNewTaskIn
import dev.deeplinks.core.homeOpenComputer
import dev.deeplinks.native.ui.v4.DlBottomSheet
import dev.deeplinks.native.ui.v4.DlIconButton
import dev.deeplinks.native.ui.v4.DlListRow
import dev.deeplinks.native.ui.v4.DlRowTrailing
import dev.deeplinks.native.ui.v4.DlStatusDot
import dev.deeplinks.native.ui.v4.DlTone

/*
 * 2.1 / 2.5 首页外框：居中的电脑名顶栏、「更多」弹层、长按工作区弹层。
 * 工作区只在首页文件夹列表里出现一次；不再有单独列出工作区的「电脑与工作区」弹层。
 */

/** 2.1 顶栏：左 电脑（7.2）· 中 电脑名 + 连接状态（同样进 7.2）· 右 更多（2.5）。 */
@Composable
internal fun HomeTopBar(
    hostName: String,
    online: Boolean,
    viaRemote: Boolean,
    offlineSinceLabel: String?,
    onOpenComputer: () -> Unit,
    onOpenMore: () -> Unit,
) {
    val s = DshS
    val status = when {
        !online -> offlineSinceLabel?.let { s.homeOfflineHeader.format(it) } ?: s.statusOffline
        viaRemote -> s.homeComputerViaRelay
        else -> s.homeComputerViaLan
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DshTouch.min + DshSpace.s16)
            .padding(horizontal = DshSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DlIconButton(LaptopOutline16, s.homeOpenComputer, onOpenComputer)
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClickLabel = s.homeOpenComputer, onClick = onOpenComputer)
                .padding(horizontal = DshSpace.s4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                hostName.ifBlank { s.deviceAndPairing },
                style = DshType.titleLarge,
                color = Dsh.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                DlStatusDot(if (online) DlTone.Ok else DlTone.Off)
                Spacer(Modifier.width(DshSpace.s4))
                Text(status, style = DshType.supporting, color = Dsh.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DlIconButton(EllipsisOutline16, s.homeMore, onOpenMore)
    }
}

/** 2.5 更多：设置 / 已归档。每个动作先关弹层再执行。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeMoreSheet(onDismiss: () -> Unit, onOpenSettings: () -> Unit, onOpenArchived: () -> Unit) {
    DlBottomSheet(onDismissRequest = onDismiss, title = DshS.homeMore) {
        HomeMoreSheetContent(
            onOpenSettings = { onDismiss(); onOpenSettings() },
            onOpenArchived = { onDismiss(); onOpenArchived() },
        )
    }
}

@Composable
internal fun HomeMoreSheetContent(onOpenSettings: () -> Unit, onOpenArchived: () -> Unit) {
    val s = DshS
    Column(Modifier.fillMaxWidth()) {
        DlListRow(title = s.settingsTitle, leading = SettingsOutline16, trailing = DlRowTrailing.Chevron, onClick = onOpenSettings)
        DlListRow(title = s.homeArchivedSessions, leading = ArchiveBoxOutline16, trailing = DlRowTrailing.Chevron, onClick = onOpenArchived)
    }
}

/** 2.5 长按工作区文件夹：在这里新建 / 删除工作区（红色，最后）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeWorkspaceSheet(
    label: String,
    online: Boolean,
    onDismiss: () -> Unit,
    onNewTask: () -> Unit,
    onDelete: () -> Unit,
) {
    DlBottomSheet(onDismissRequest = onDismiss, title = label) {
        HomeWorkspaceSheetContent(
            label = label,
            online = online,
            onNewTask = { onDismiss(); onNewTask() },
            onDelete = { onDismiss(); onDelete() },
        )
    }
}

@Composable
internal fun HomeWorkspaceSheetContent(label: String, online: Boolean, onNewTask: () -> Unit, onDelete: () -> Unit) {
    val s = DshS
    Column(Modifier.fillMaxWidth()) {
        DlListRow(title = s.homeNewTaskIn.format(label), leading = PlusOutline16, enabled = online, onClick = onNewTask)
        DlListRow(title = s.homeDeleteWorkspaceNamed.format(label), leading = TrashOutline16, danger = true, onClick = onDelete)
    }
}
