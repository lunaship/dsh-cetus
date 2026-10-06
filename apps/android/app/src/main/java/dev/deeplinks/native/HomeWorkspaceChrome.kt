package dev.deeplinks.native

import androidx.compose.foundation.clickable
import dev.deeplinks.native.ui.v4.DlSpinner
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.homeComputerViaLan
import dev.deeplinks.core.homeComputerViaRelay
import dev.deeplinks.core.homeNewTaskIn
import dev.deeplinks.core.homeOpenComputer
import dev.deeplinks.native.ui.v4.DlBottomSheet
import dev.deeplinks.native.ui.v4.DlIconButton
import dev.deeplinks.native.ui.v4.DlListRow
import dev.deeplinks.native.ui.v4.DlStatusDot
import dev.deeplinks.native.ui.v4.DlTone

/*
 * 2.1 / 2.5 首页外框：居中的电脑名顶栏、「更多」弹层、长按工作区弹层。
 * 工作区只在首页文件夹列表里出现一次；不再有单独列出工作区的「电脑与工作区」弹层。
 */

/** 2.1 顶栏：左 电脑（7.2）· 中 电脑名 + 连接状态（同样进 7.2）· 右 设置。 */
@Composable
internal fun HomeTopBar(
    hostName: String,
    online: Boolean,
    viaRemote: Boolean,
    offlineSinceLabel: String?,
    onOpenComputer: () -> Unit,
    onOpenSettings: () -> Unit,
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
        DlIconButton(SettingsOutline16, s.settingsTitle, onOpenSettings)
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

/**
 * 2.1 文件夹里的会话：只有一行标题。左边和文件夹图标同宽的槽位里，运行中画小转圈，
 * 中断 / 失败画灰 / 红点，其余留空；状态文字交给读屏。不显示时间和预览。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeFolderSessionRow(
    session: MobileSession,
    online: Boolean,
    goalSummary: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val texts = homeInboxTexts(session, pending = null, goalSummary = goalSummary, offline = !online)
    val status = texts.status
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .semantics { if (status != null) stateDescription = status }
            .heightIn(min = DshTouch.min)
            .padding(start = DshSpace.s20, end = DshSpace.s20),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(DshIconSize.md), contentAlignment = Alignment.Center) {
            when {
                texts.running -> DlSpinner()
                texts.tone == DlTone.Off || texts.tone == DlTone.Err -> DlStatusDot(texts.tone)
            }
        }
        Spacer(Modifier.width(DshSpace.s12))
        Text(
            displaySessionTitle(session.title),
            style = DshType.body,
            color = Dsh.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 会话标题与文件夹名左对齐的缩进：左边距 + 图标宽 + 间距。 */
private val HomeFolderIndent = DshSpace.s20 + DshIconSize.md + DshSpace.s12

/** 文件夹尾部「显示全部 N 个 / 收起」：品牌色小字，与会话标题左对齐。 */
@Composable
internal fun HomeFolderMoreRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = DshType.supporting,
        color = Dsh.brand400,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = DshTouch.min)
            .padding(start = HomeFolderIndent, end = DshSpace.s20, top = DshSpace.s12, bottom = DshSpace.s12),
    )
}

/** 2.1 「工作区」大标题：首页唯一的粗体大字，下面是文件夹列表。 */
@Composable
internal fun HomeWorkspacesTitle(title: String) {
    Text(
        title,
        style = DshType.titleLarge,
        fontWeight = FontWeight.Bold,
        color = Dsh.labelPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { heading() }
            .padding(start = DshSpace.s20, end = DshSpace.s20, top = DshSpace.s24, bottom = DshSpace.s4),
    )
}
