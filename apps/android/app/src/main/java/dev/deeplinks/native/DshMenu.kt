package dev.deeplinks.native

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deeplinks.core.dshRipple
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType

// ---------- DSH 风格菜单浮层（M3 DropdownMenu：容器色 + 阴影分层，无描边） ----------

internal data class DshMenuItem(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val danger: Boolean = false,
    val selected: Boolean = false,
    /** 在这一项之前画一条分隔线（首页筛选菜单里分组：工作区 / 添加 / 已归档 / 删除）。 */
    val dividerBefore: Boolean = false,
    /**
     * 行尾动作槽（2026-09-28 重设计新增）：首页工作区筛选菜单要把「在这里新建 / 移除工作区」
     * 挂在每一项尾部，而菜单本身仍是「点一下选中」。槽内的点击自己消费，不会触发 [onClick]。
     * 位置在 [onClick] 之前，好让既有的尾随 lambda 写法（`DshMenuItem(i, l) { ... }`）继续绑到 onClick。
     */
    val trailingContent: (@Composable () -> Unit)? = null,
    val onClick: () -> Unit,
)

@Composable
internal fun DshMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<DshMenuItem>,
    offset: androidx.compose.ui.unit.DpOffset = androidx.compose.ui.unit.DpOffset.Zero,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
        containerColor = Dsh.bgCard,
        shape = RoundedCornerShape(DshRadius.container),
        tonalElevation = 0.dp,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .width(220.dp)
                .padding(vertical = DshSpace.s4)
        ) {
            items.forEach { item ->
                if (item.dividerBefore) {
                    HorizontalDivider(
                        color = Dsh.borderSubtle,
                        modifier = Modifier.padding(vertical = DshSpace.s4),
                    )
                }
                val interaction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(DshRadius.control))
                        .background(
                            when {
                                item.selected -> Dsh.bgSubtle
                                else -> Color.Transparent
                            }
                        )
                        .semantics {
                            role = Role.Button
                            contentDescription = item.label
                        }
                        .clickable(interactionSource = interaction, indication = dshRipple(), onClick = item.onClick)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        item.icon,
                        contentDescription = null,
                        tint = if (item.danger) Dsh.error else Dsh.labelSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        item.label,
                        color = if (item.danger) Dsh.error else Dsh.labelPrimary,
                        style = DshType.body,
                        lineHeight = 20.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (item.selected) {
                        Icon(
                            CheckOutline16,
                            contentDescription = null,
                            tint = Dsh.labelPrimary,
                            modifier = Modifier.size(DshIconSize.sm),
                        )
                    }
                    if (item.trailingContent != null) {
                        Spacer(Modifier.width(DshSpace.s4))
                        item.trailingContent()
                    }
                }
            }
        }
    }
}
