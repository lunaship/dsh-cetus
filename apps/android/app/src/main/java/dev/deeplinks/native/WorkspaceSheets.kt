package dev.deeplinks.native

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.core.subagentSheetFootnote
import dev.deeplinks.native.ui.v4.DlBottomSheet
import dev.deeplinks.native.ui.v4.DlInsetColor
import dev.deeplinks.native.ui.v4.DlListRow
import dev.deeplinks.native.ui.v4.DlPill

/** 弹层里的搜索框（3.2、5.2）：surface1 胶囊输入条。 */
@Composable
internal fun SheetSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DshTouch.min)
            .clip(DlPill)
            .background(DlInsetColor)
            .padding(start = DshSpace.s16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(SearchOutline16, contentDescription = null, tint = Dsh.labelSecondary, modifier = Modifier.size(DshIconSize.md))
        Spacer(Modifier.width(DshSpace.s12))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = DshType.body.copy(color = Dsh.labelPrimary),
            cursorBrush = SolidColor(Dsh.brand400),
            modifier = Modifier
                .weight(1f)
                .then(if (value.isEmpty()) Modifier.padding(end = DshSpace.s16) else Modifier),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, color = Dsh.tertiaryText, style = DshType.body)
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(DshTouch.min)
                    .semantics {
                        role = Role.Button
                        contentDescription = L.clearSearch
                    }
                    .clickable { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(CloseOutline16, contentDescription = null, tint = Dsh.labelSecondary, modifier = Modifier.size(DshIconSize.sm))
            }
        }
    }
}

// ---------- 子智能体 ----------

/** 5.8 子代理弹层：列表 + 说明；当前是子代理会话时多一行「返回父会话」。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubagentBottomSheet(
    sessions: List<MobileSession>,
    currentSession: MobileSession?,
    currentSessionId: String?,
    onSelectSession: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val anchorParent = currentSession?.parentSessionId ?: currentSessionId
    val nodes = remember(sessions, anchorParent) { buildSubagentTree(sessions, anchorParent) }
    val parentOfCurrent = currentSession?.parentSessionId
    DlBottomSheet(onDismissRequest = onDismiss, title = L.subagents) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            SubagentTree(nodes, onSelect = {
                onDismiss()
                onSelectSession(it)
            })
        }
        if (!parentOfCurrent.isNullOrBlank()) {
            DlListRow(
                title = L.returnToParentSession,
                leading = ArrowLeftOutline16,
                onClick = {
                    onDismiss()
                    onSelectSession(parentOfCurrent)
                },
            )
        }
        if (nodes.isNotEmpty()) {
            Text(
                DshS.subagentSheetFootnote,
                style = DshType.supporting,
                color = Dsh.labelSecondary,
                modifier = Modifier.padding(horizontal = DshSpace.s24, vertical = DshSpace.s8),
            )
        }
    }
}
