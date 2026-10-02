package dev.deeplinks.native.ui.v4

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.native.ChevronDownOutline16
import dev.deeplinks.native.ChevronUpOutline16
import dev.deeplinks.native.DshIconSize
import dev.deeplinks.native.DshRadius
import dev.deeplinks.native.DshSpace

/** 状态槽能显示的状态，按优先级从高到低（PLAN 第 2 节：断线 > 待处理 > 目标 > 预览）。 */
enum class DlStatusKind { Disconnected, Pending, Goal, Preview }

/** 一次只显示一个状态：取优先级最高的那一条。 */
fun <T> List<T>.topStatus(kindOf: (T) -> DlStatusKind): T? = minByOrNull { kindOf(it).ordinal }

/**
 * v4 状态槽（4.1、4.5、4.8）：对话页顶栏下方，平时一行摘要，点开展开。
 * 底色：中性 surface1，等你 waitSoft，失败 / 断线 errSoft。
 * [expandedContent] 为空时不可展开。
 */
@Composable
fun DlStatusSlot(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    meta: String? = null,
    tone: DlTone = DlTone.Neutral,
    /** 行尾动作（如断线时的「重试」文字按钮）；有行尾动作时不显示展开箭头。 */
    trailing: (@Composable RowScope.() -> Unit)? = null,
    expanded: Boolean = false,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    expandedContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val container = when (tone) {
        DlTone.Wait -> Dsh.waitSoft
        DlTone.Err -> Dsh.errSoft
        else -> Dsh.surface1
    }
    val shape = RoundedCornerShape(DshRadius.block)
    val expandable = expandedContent != null && onExpandedChange != null
    val showExpanded = expandable && expanded
    Column(
        modifier = modifier
            .padding(horizontal = DshSpace.s12, vertical = DshSpace.s4)
            .fillMaxWidth()
            .clip(shape)
            .background(container)
            .then(
                if (expandable) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) DshS.collapse else DshS.expand,
                    ) { onExpandedChange?.invoke(!expanded) }
                } else {
                    Modifier
                },
            )
            .padding(if (showExpanded) DshSpace.s16 else DshSpace.s12),
        verticalArrangement = Arrangement.spacedBy(DshSpace.s12),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(DshSpace.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                leading != null -> leading()
                icon != null -> Icon(
                    icon,
                    contentDescription = null,
                    tint = if (tone == DlTone.Neutral) Dsh.labelSecondary else tone.color,
                    modifier = Modifier.size(DshIconSize.md),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = if (showExpanded) DshType.bodyStrong else DshType.body,
                    color = Dsh.labelPrimary,
                    maxLines = if (showExpanded) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta != null) {
                    Text(
                        meta,
                        style = DshType.supporting,
                        color = Dsh.labelSecondary,
                        maxLines = if (showExpanded) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                trailing()
            } else if (expandable) {
                Icon(
                    if (expanded) ChevronUpOutline16 else ChevronDownOutline16,
                    contentDescription = null,
                    tint = Dsh.labelSecondary,
                    modifier = Modifier.size(DshIconSize.sm),
                )
            }
        }
        if (expandedContent != null) {
            AnimatedVisibility(visible = showExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(DshSpace.s12)) { expandedContent() }
            }
        }
    }
}
