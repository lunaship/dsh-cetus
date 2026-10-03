package dev.deeplinks.native

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.core.subagentDone
import dev.deeplinks.native.ui.v4.DlLabelStrong
import dev.deeplinks.native.ui.v4.DlSize
import dev.deeplinks.native.ui.v4.DlSpinner

data class SubagentNode(
    val sessionId: String,
    val title: String,
    val running: Boolean,
    val children: List<SubagentNode> = emptyList(),
)

/** 列表里没有任何 parentSessionId / origin=subagent 时，认为 Host 没有这个字段。空列表不算。 */
fun hostExposesSubagentParent(sessions: List<MobileSession>): Boolean =
    sessions.isEmpty() || sessions.any { it.origin == "subagent" || !it.parentSessionId.isNullOrBlank() }

fun runningSubagentCount(sessions: List<MobileSession>, parentId: String, seen: Set<String> = emptySet()): Int {
    if (parentId.isBlank() || parentId in seen) return 0
    val nextSeen = seen + parentId
    val children = sessions.filter { it.origin == "subagent" && it.parentSessionId == parentId }
    return children.count { it.running } + children.sumOf { runningSubagentCount(sessions, it.sessionId, nextSeen) }
}

fun buildSubagentTree(sessions: List<MobileSession>, rootId: String?, seen: Set<String> = emptySet()): List<SubagentNode> {
    if (rootId.isNullOrBlank() || rootId in seen || !hostExposesSubagentParent(sessions)) return emptyList()
    val nextSeen = seen + rootId
    return sessions
        .filter { it.origin == "subagent" && it.parentSessionId == rootId }
        .sortedByDescending { it.updatedAt }
        .map { child ->
            SubagentNode(
                sessionId = child.sessionId,
                title = child.title,
                running = child.running,
                children = buildSubagentTree(sessions, child.sessionId, nextSeen),
            )
        }
}

/**
 * 5.8 子代理列表：名字正文色粗体；状态只用转圈（运行中）或绿色「完成」标签。
 * 子节点缩进一级，整行可点打开该子代理的会话。
 */
@Composable
internal fun SubagentTree(
    nodes: List<SubagentNode>,
    onSelect: (String) -> Unit,
    depth: Int = 0,
) {
    if (nodes.isEmpty() && depth == 0) {
        Text(
            L.noSubagentSessions,
            color = Dsh.labelSecondary,
            style = DshType.supporting,
            modifier = Modifier.padding(horizontal = DshSpace.s24, vertical = DshSpace.s12),
        )
        return
    }
    Column(Modifier.fillMaxWidth()) {
        nodes.forEach { node ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = DlSize.rowSingle)
                    .clickable { onSelect(node.sessionId) }
                    .padding(start = DshSpace.s20 + DshSpace.s16 * depth, end = DshSpace.s20, top = DshSpace.s8, bottom = DshSpace.s8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DshSpace.s12),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        node.title,
                        color = Dsh.labelPrimary,
                        style = DshType.bodyStrong,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (node.running) Text(L.runningStatus, color = Dsh.labelSecondary, style = DshType.supporting)
                }
                if (node.running) {
                    DlSpinner()
                } else {
                    Text(DshS.subagentDone, color = Dsh.ok, style = DlLabelStrong)
                }
            }
            if (node.children.isNotEmpty()) SubagentTree(node.children, onSelect, depth + 1)
        }
    }
}
