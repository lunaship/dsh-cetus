package dev.deeplinks.native

import dev.deeplinks.native.DshIconSize
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import dev.deeplinks.core.DshType

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import dev.deeplinks.core.L
import dev.deeplinks.native.ui.DshListActionRow
import dev.deeplinks.native.ui.DshListSection
import dev.deeplinks.native.ui.DshSheet

/** 面板里的搜索框：分组卡片同色的圆角输入条，放在冷灰面板底上。 */
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
            .clip(RoundedCornerShape(DshRadius.container))
            .background(Dsh.bgInput)
            .padding(start = DshSpace.s12),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(SearchOutline16, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(DshIconSize.sm))
        Spacer(Modifier.width(DshSpace.s8))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = DshType.body.copy(color = Dsh.labelPrimary),
            cursorBrush = SolidColor(Dsh.brand400),
            modifier = Modifier
                .weight(1f)
                .padding(end = if (value.isEmpty()) 12.dp else 0.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, color = Dsh.labelTertiary, style = DshType.body)
                    inner()
                }
            }
        )
        if (value.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = L.clearSearch
                    }
                    .clickable { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(CloseOutline16, contentDescription = null, tint = Dsh.labelTertiary, modifier = Modifier.size(DshIconSize.sm))
            }
        }
    }
}

// ---------- 子智能体 ----------

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
    DshSheet(
        onDismiss = onDismiss,
        title = L.subagents,
        subtitle = if (nodes.isEmpty()) L.noSubagentSessions else L.subagentSheetSummary.format(nodes.size),
    ) {
        if (nodes.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SubagentTree(nodes, onSelect = {
                    onDismiss()
                    onSelectSession(it)
                })
            }
        }
        if (!parentOfCurrent.isNullOrBlank()) {
            DshListSection {
                DshListActionRow(
                    label = L.returnToParentSession,
                    onClick = {
                        onDismiss()
                        onSelectSession(parentOfCurrent)
                    },
                )
            }
        }
    }
}
