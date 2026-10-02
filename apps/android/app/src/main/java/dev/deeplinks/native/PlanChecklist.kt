package dev.deeplinks.native

import dev.deeplinks.native.ui.dshCardSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.core.goalCollapse
import dev.deeplinks.core.goalExpand
import dev.deeplinks.core.planActive
import dev.deeplinks.core.planCollapsed
import dev.deeplinks.core.planCollapsedCurrent
import dev.deeplinks.core.planDone
import dev.deeplinks.core.planEmpty
import dev.deeplinks.core.planPending
import dev.deeplinks.native.ui.DshIconAction

enum class PlanItemKind { Pending, Active, Done }

/** 状态口径与消息里的 todo 一致，不再解析一份 JSON。 */
fun planItemKind(status: String): PlanItemKind = when (status) {
    "in_progress", "inprogress", "running", "active", "progress" -> PlanItemKind.Active
    "done", "completed", "complete" -> PlanItemKind.Done
    else -> PlanItemKind.Pending
}

fun latestPlanItems(messages: List<MobileMessage>): List<MobileTodoItem> {
    for (message in messages.asReversed()) {
        if (message.role == "todo") return message.todos
    }
    return emptyList()
}

/** 收起时「3/7 · 正在：……」。没有进行中的项就只显示完成数。 */
fun planCollapsedText(
    items: List<MobileTodoItem>,
    counts: String,
    withCurrent: String,
): String {
    val done = items.count { planItemKind(it.status) == PlanItemKind.Done }
    val current = items.firstOrNull { planItemKind(it.status) == PlanItemKind.Active }?.content?.trim().orEmpty()
    return if (current.isEmpty()) {
        counts.format(done, items.size)
    } else {
        withCurrent.format(done, items.size, current)
    }
}

@Composable
internal fun PlanChecklist(
    items: List<MobileTodoItem>,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onToggle: () -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpace.s12, vertical = DshSpace.s4)
            .dshCardSurface() // 与首页卡片同一卡面（白卡 + 发丝边 + 轻阴影），在实底顶栏下也分得出层
            .padding(horizontal = DshSpace.s12, vertical = DshSpace.s8),
        verticalArrangement = Arrangement.spacedBy(DshSpace.s4),
    ) {
        if (items.isEmpty()) {
            Text(L.planEmpty, color = Dsh.labelSecondary, style = DshType.caption)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DshSpace.s8)) {
            Text(
                planCollapsedText(items, L.planCollapsed, L.planCollapsedCurrent),
                color = Dsh.labelPrimary,
                style = DshType.caption,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DshIconAction(
                if (expanded) ChevronUpOutline16 else ChevronDownOutline16,
                if (expanded) L.goalCollapse else L.goalExpand,
                onToggle,
                iconSize = DshIconSize.sm,
                visualSize = DshSpace.s32,
            )
        }
        if (expanded) {
            items.forEach { item -> PlanChecklistRow(item) }
        }
    }
}

@Composable
private fun PlanChecklistRow(item: MobileTodoItem) {
    val kind = planItemKind(item.status)
    val status = when (kind) {
        PlanItemKind.Pending -> L.planPending
        PlanItemKind.Active -> L.planActive
        PlanItemKind.Done -> L.planDone
    }
    val highlight = kind == PlanItemKind.Active
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.control))
            .background(if (highlight) Dsh.accentIcon.copy(alpha = 0.12f) else Dsh.bgInput)
            .padding(horizontal = DshSpace.s8, vertical = DshSpace.s4),
        horizontalArrangement = Arrangement.spacedBy(DshSpace.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(status, color = if (highlight) Dsh.accentIcon else Dsh.labelSecondary, style = DshType.microMedium)
        Text(
            item.content,
            color = Dsh.labelPrimary,
            style = DshType.caption,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 有清单才出现在目标卡下面。宽屏也留在这里，不放进改动侧栏。 */
@Composable
internal fun SessionPlanChecklist(messages: List<MobileMessage>, modifier: Modifier = Modifier) {
    val items = remember(messages) { latestPlanItems(messages) }
    if (items.isEmpty()) return
    var expanded by remember(items) { mutableStateOf(false) }
    PlanChecklist(items = items, modifier = modifier, expanded = expanded, onToggle = { expanded = !expanded })
}
