package dev.deeplinks.native

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.core.subagentIdle

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

@Composable
internal fun SubagentTree(
    nodes: List<SubagentNode>,
    onSelect: (String) -> Unit,
    depth: Int = 0,
) {
    if (nodes.isEmpty() && depth == 0) {
        Text(L.noSubagentSessions, color = Dsh.labelSecondary, style = DshType.caption)
        return
    }
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(DshSpace.s4)) {
        nodes.forEach { node ->
            val inset = if (depth == 0) Modifier else Modifier.padding(start = DshSpace.s16)
            Column(inset.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(node.sessionId) }
                        .padding(vertical = DshSpace.s4),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(DshSpace.s8),
                ) {
                    Text(
                        node.title,
                        color = Dsh.labelPrimary,
                        style = DshType.caption,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (node.running) L.runningStatus else L.subagentIdle,
                        color = if (node.running) Dsh.accentIcon else Dsh.labelSecondary,
                        style = DshType.microMedium,
                    )
                }
                if (node.children.isNotEmpty()) {
                    SubagentTree(node.children, onSelect, depth + 1)
                }
            }
        }
    }
}
