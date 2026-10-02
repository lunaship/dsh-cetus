package dev.deeplinks.native

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.core.planActive
import dev.deeplinks.core.planDone
import dev.deeplinks.core.planEmpty
import dev.deeplinks.core.planPending
import dev.deeplinks.native.ui.v4.DlSpinner

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

/**
 * v4 4.5：状态槽展开后的计划清单。上面一根进度条，下面每项一行：
 * 完成项是品牌色勾选框 + 灰字（不加删除线），进行中是转圈 + 粗体，待办是空框。
 */
@Composable
internal fun PlanChecklist(items: List<MobileTodoItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) {
        Text(L.planEmpty, color = Dsh.labelSecondary, style = DshType.supporting, modifier = modifier)
        return
    }
    val done = items.count { planItemKind(it.status) == PlanItemKind.Done }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DshSpace.s12)) {
        PlanProgressBar(done.toFloat() / items.size)
        Column(verticalArrangement = Arrangement.spacedBy(DshSpace.s8)) {
            items.forEach { item -> PlanChecklistRow(item) }
        }
    }
}

@Composable
private fun PlanProgressBar(fraction: Float) {
    val shape = RoundedCornerShape(DshRadius.control)
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(shape)
            .background(Dsh.surface2),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(shape)
                .background(Dsh.brand400),
        )
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
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "$status ${item.content}" },
        horizontalArrangement = Arrangement.spacedBy(DshSpace.s12),
        verticalAlignment = Alignment.Top,
    ) {
        PlanCheckBox(kind)
        Text(
            item.content,
            color = if (kind == PlanItemKind.Done) Dsh.labelSecondary else Dsh.labelPrimary,
            style = DshType.supporting,
            fontWeight = if (kind == PlanItemKind.Active) FontWeight.SemiBold else null,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlanCheckBox(kind: PlanItemKind) {
    val shape = RoundedCornerShape(DshRadius.control)
    Box(Modifier.size(DshIconSize.md), contentAlignment = Alignment.Center) {
        when (kind) {
            PlanItemKind.Active -> DlSpinner()
            PlanItemKind.Done -> Box(
                Modifier.size(DshIconSize.md).clip(shape).background(Dsh.brand400),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CheckOutline16, contentDescription = null, tint = Dsh.onBrand, modifier = Modifier.size(DshIconSize.xs))
            }
            PlanItemKind.Pending -> Box(Modifier.size(DshIconSize.md).border(2.dp, Dsh.labelTertiary, shape))
        }
    }
}
