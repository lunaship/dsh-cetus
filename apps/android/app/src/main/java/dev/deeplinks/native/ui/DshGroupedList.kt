package dev.deeplinks.native.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.dshRipple
import dev.deeplinks.core.tabularNums
import dev.deeplinks.native.CheckOutline16
import dev.deeplinks.native.ChevronDownOutline14
import dev.deeplinks.native.ChevronRightOutline14
import dev.deeplinks.native.DshRadius

/**
 * Section 与行骨架（docs/visual-rules.md 第五节：设置、设备、Sheet 复用同一行结构）。
 *
 * 一页 = [DshPageScaffold] 的画布底 + 若干 [DshSection]。Section 默认 **Flat**：
 * 行直接落在画布上，行间发丝线分组；只有总结、警告、独立账户或设备摘要才用
 * **Tonal**（[Dsh.bgSubtle] 容器 + container 12dp 圆角）。
 *
 * 行只有一种骨架 [DshListRow]：图标 · 标题 / 副标题 · 取值 · 尾标，
 * 开关、下拉、按钮行都是它的变体。行首图标默认中性灰（与侧栏一致），
 * 品牌蓝只留给可执行的操作行和选中勾。行间发丝线由 Section 自动画，
 * 起点跟随下一行的文字起点（有图标时让开图标）。
 *
 * [DshListSection] / [DshGroupedPage] / [DshLargeTitle] 是迁移期兼容包装，
 * 调用点清零后删除。
 */

private val RowPaddingH = 16.dp
private val RowPaddingV = 12.dp
private val IconSlot = 22.dp
/** 自有图标是满幅绘制（无内边距），18dp 与原先 22dp 的 Material 图标视觉等大。 */
private val IconSize = 18.dp
private val IconGap = 14.dp
private val RowMinHeight = 52.dp
/** 右侧取值的最大宽度：取值贴右、尾标成一条竖线；超长时截断取值而不是挤压标题。 */
private val ValueMaxWidth = 168.dp
private val TextInsetWithIcon = RowPaddingH + IconSlot + IconGap

private data class DividerInset(val start: Dp) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@DividerInset
}

/** 行的根节点声明分隔线起点；卡片用它画「上一行与本行之间」的那根线。 */
private fun Modifier.dividerInset(hasIcon: Boolean): Modifier =
    then(DividerInset(if (hasIcon) TextInsetWithIcon else RowPaddingH))

/**
 * 分组页容器（迁移期兼容包装）：内部转发到 [DshPageScaffold]——画布底、独立滚动、
 * 手机 16dp 边距、大屏 720dp 居中；不再强制 grouped card 视觉。
 *
 * 系统栏 inset 由调用方（设置 / 设备页）自行消费（[consumeSystemInsets] = false），
 * 批次 2/3 把它们迁到 [DshPageScaffold] 后，本包装删除。
 */
@Composable
fun DshGroupedPage(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DshPageScaffold(
        title = "",
        modifier = modifier,
        consumeSystemInsets = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 32.dp),
            content = content,
        )
    }
}

/** Section 容器策略：默认扁平；tonal 必须有独立分组理由（总结 / 警告 / 独立数据块）。 */
enum class DshSectionContainer {
    /** 行直接落在画布上，行间发丝线分组。 */
    Flat,

    /** tonal 容器（bgSubtle + container 圆角）：总结、警告、独立账户或设备摘要。 */
    Tonal,
}

/**
 * 一个 Section：标题（可带右侧文字操作）+ 行容器 + 页脚。
 * [content] 里的每个直接子节点是一行；空 Section 不画容器。
 */
@Composable
fun DshSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    container: DshSectionContainer = DshSectionContainer.Flat,
    headerAction: String? = null,
    headerActionDanger: Boolean = false,
    headerActionEnabled: Boolean = true,
    onHeaderAction: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = if (header != null) 16.dp else 12.dp)) {
        if (header != null) {
            DshSectionHeader(
                title = header,
                actionLabel = headerAction,
                actionDanger = headerActionDanger,
                actionEnabled = headerActionEnabled,
                onAction = onHeaderAction,
            )
        }
        DshSectionRows(tonal = container == DshSectionContainer.Tonal, content = content)
        if (footer != null) {
            Text(
                footer,
                color = Dsh.labelTertiary,
                style = DshType.captionRelaxed,
                modifier = Modifier.padding(start = RowPaddingH, end = RowPaddingH, top = 6.dp),
            )
        }
    }
}

/**
 * Section（迁移期兼容包装）：与 [DshSection] 同一件，[tonal] = true 时使用 tonal 容器。
 */
@Composable
fun DshListSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    headerAction: String? = null,
    headerActionDanger: Boolean = false,
    headerActionEnabled: Boolean = true,
    onHeaderAction: (() -> Unit)? = null,
    tonal: Boolean = false,
    content: @Composable () -> Unit,
) {
    DshSection(
        modifier = modifier,
        header = header,
        footer = footer,
        container = if (tonal) DshSectionContainer.Tonal else DshSectionContainer.Flat,
        headerAction = headerAction,
        headerActionDanger = headerActionDanger,
        headerActionEnabled = headerActionEnabled,
        onHeaderAction = onHeaderAction,
        content = content,
    )
}

/**
 * Section 标题（小号灰字，与行内文字对齐）；Section 外的自定义内容也用它起头。
 *
 * 状态变体（任务首页的分区头）：[leading] 放状态圆点 / 图标，[count] 是计数 pill——
 * 颜色只作辅助，语义由文字承担（docs/visual-rules.md 第五节）。
 */
@Composable
fun DshSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actionDanger: Boolean = false,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    count: Int? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .padding(start = RowPaddingH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        Text(
            title,
            color = Dsh.labelTertiary,
            style = DshType.titleSmall,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (count != null && count > 0) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(DshRadius.full))
                    .background(Dsh.bgSubtle)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("$count", color = Dsh.labelTertiary, style = DshType.captionRelaxed.tabularNums())
            }
        }
        if (actionLabel != null && onAction != null) {
            val color = when {
                !actionEnabled -> Dsh.labelDimmed
                actionDanger -> Dsh.error
                else -> Dsh.brand400
            }
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(DshRadius.control))
                    .clickable(enabled = actionEnabled, role = Role.Button, onClick = onAction)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(actionLabel, color = color, style = DshType.titleSmall)
            }
        }
    }
}

/**
 * Section 行容器：自排版子节点，逐行画发丝分隔线（0 高度的节点——例如弹层锚点——不参与）。
 * [tonal] 时加 bgSubtle 底 + container 圆角；Flat 时行直接落在画布上。
 */
@Composable
private fun DshSectionRows(
    tonal: Boolean,
    content: @Composable () -> Unit,
) {
    val dividerColor = Dsh.borderSubtle
    val boundaries = remember { mutableListOf<Pair<Float, Float>>() }
    Layout(
        content = content,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (tonal) {
                    Modifier
                        .clip(RoundedCornerShape(DshRadius.container))
                        .background(Dsh.bgSubtle)
                } else {
                    Modifier
                },
            )
            .drawWithContent {
                drawContent()
                val stroke = 0.5.dp.toPx()
                boundaries.forEach { (y, start) ->
                    val (x0, x1) = if (layoutDirection == LayoutDirection.Rtl) {
                        0f to size.width - start
                    } else {
                        start to size.width
                    }
                    drawLine(dividerColor, Offset(x0, y), Offset(x1, y), strokeWidth = stroke)
                }
            },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val rowConstraints = constraints.copy(minWidth = width, minHeight = 0)
        val placeables = measurables.map { it.measure(rowConstraints) }
        val insets = measurables.map { (it.parentData as? DividerInset)?.start ?: RowPaddingH }
        val height = placeables.sumOf { it.height }
        layout(width, height) {
            boundaries.clear()
            var y = 0
            var seenRow = false
            placeables.forEachIndexed { index, placeable ->
                if (placeable.height > 0) {
                    if (seenRow) boundaries += y.toFloat() to insets[index].toPx()
                    seenRow = true
                }
                placeable.placeRelative(0, y)
                y += placeable.height
            }
        }
    }
}

/** 行尾标记。 */
enum class DshListTrailing { None, Chevron, Check, Select }

/**
 * 分组行骨架。[onClick] 为空即只读行；[value] 是右侧当前值（灰字）；
 * [error] 显示在副标题下方，带 [onRetry] 时给出行内重试。
 */
@Composable
fun DshListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Dsh.labelSecondary,
    value: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: DshListTrailing = if (onClick != null) DshListTrailing.Chevron else DshListTrailing.None,
    leading: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val clickable = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interaction,
            indication = dshRipple(),
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    DshListRowLayout(
        modifier = modifier.dividerInset(icon != null || leading != null).then(clickable),
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconTint = iconTint,
        value = value,
        destructive = destructive,
        enabled = enabled,
        error = error,
        onRetry = onRetry,
        trailing = trailing,
        leading = leading,
        trailingContent = trailingContent,
    )
}

@Composable
private fun DshListRowLayout(
    modifier: Modifier,
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    iconTint: Color,
    value: String?,
    destructive: Boolean,
    enabled: Boolean,
    error: String?,
    onRetry: (() -> Unit)?,
    trailing: DshListTrailing,
    leading: (@Composable () -> Unit)?,
    trailingContent: (@Composable () -> Unit)?,
) {
    val titleColor = when {
        !enabled -> Dsh.labelTertiary
        destructive -> Dsh.error
        else -> Dsh.labelPrimary
    }
    val hasLeading = leading != null || icon != null
    Column(modifier = modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .padding(horizontal = RowPaddingH, vertical = RowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> {
                Box(Modifier.size(IconSlot), contentAlignment = Alignment.Center) { leading() }
                Spacer(Modifier.width(IconGap))
            }
            icon != null -> {
                Box(Modifier.size(IconSlot), contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (destructive) Dsh.error else iconTint,
                        modifier = Modifier.size(IconSize),
                    )
                }
                Spacer(Modifier.width(IconGap))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(title, color = titleColor, style = DshType.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = Dsh.labelTertiary, style = DshType.supporting, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!value.isNullOrBlank()) {
            Spacer(Modifier.width(12.dp))
            Text(
                value,
                color = Dsh.labelTertiary,
                style = DshType.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = ValueMaxWidth),
            )
        }
        if (trailingContent != null) {
            Spacer(Modifier.width(12.dp))
            trailingContent()
        }
        DshListTrailingMark(trailing)
    }
        if (!error.isNullOrBlank()) {
            DshListRowError(
                error = error,
                onRetry = onRetry,
                modifier = Modifier.padding(
                    start = RowPaddingH + if (hasLeading) IconSlot + IconGap else 0.dp,
                    end = RowPaddingH,
                    bottom = 10.dp,
                ),
            )
        }
    }
}

@Composable
private fun DshListTrailingMark(trailing: DshListTrailing) {
    val (icon, tint, size) = when (trailing) {
        DshListTrailing.None -> return
        DshListTrailing.Chevron -> Triple(ChevronRightOutline14, Dsh.labelTertiary, 16.dp)
        DshListTrailing.Check -> Triple(CheckOutline16, Dsh.brand400, 18.dp)
        DshListTrailing.Select -> Triple(ChevronDownOutline14, Dsh.labelTertiary, 16.dp)
    }
    Spacer(Modifier.width(6.dp))
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

@Composable
private fun DshListRowError(error: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    val s = DshS
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            s.saveFailedWithMessage.format(error),
            color = Dsh.error,
            style = DshType.captionRelaxed,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            Box(
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .clip(RoundedCornerShape(DshRadius.sm))
                    .clickable(role = Role.Button, onClick = onRetry)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(s.retry, color = Dsh.brand400, style = DshType.titleSmall)
            }
        }
    }
}

/** 开关行：整行是一个 Switch 语义节点，点哪里都切换。 */
@Composable
fun DshSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Dsh.labelSecondary,
    enabled: Boolean = true,
) {
    DshListRowLayout(
        modifier = modifier
            .dividerInset(icon != null)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = dshRipple(),
                onValueChange = onCheckedChange,
            ),
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconTint = iconTint,
        value = null,
        destructive = false,
        enabled = enabled,
        error = null,
        onRetry = null,
        trailing = DshListTrailing.None,
        leading = null,
        trailingContent = {
            Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = dshSwitchColors())
        },
    )
}

@Composable
fun dshSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = Dsh.onBrand,
    checkedTrackColor = Dsh.brand400,
    checkedBorderColor = Dsh.brand400,
    uncheckedThumbColor = Dsh.labelSecondary,
    uncheckedTrackColor = Dsh.bgSubtle,
    uncheckedBorderColor = Dsh.borderStrong,
)

/**
 * 下拉选择行：当前值 + 下箭头（区别于跳页的右箭头），点开在行尾弹出选项，
 * 选中项打品牌色勾。[saving] 时锁定并把取值换成「保存中」。
 */
@Composable
fun DshSelectRow(
    title: String,
    value: String,
    options: List<Pair<String, String>>,
    selectedId: String?,
    onSelect: (label: String, id: String) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Dsh.labelSecondary,
    saving: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val s = DshS
    var expanded by remember { mutableStateOf(false) }
    Box(modifier.dividerInset(icon != null)) {
        DshListRowLayout(
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = dshRipple(),
                enabled = !saving,
                role = Role.DropdownList,
                onClick = { expanded = true },
            ),
            title = title,
            subtitle = subtitle,
            icon = icon,
            iconTint = iconTint,
            value = if (saving) s.saving else value,
            destructive = false,
            enabled = true,
            error = error,
            onRetry = onRetry,
            trailing = DshListTrailing.Select,
            leading = null,
            trailingContent = null,
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = RowPaddingH),
        ) {
            DshOptionsMenu(
                expanded = expanded,
                onDismiss = { expanded = false },
                options = options,
                selectedId = selectedId,
                onSelect = { label, id ->
                    expanded = false
                    onSelect(label, id)
                },
            )
        }
    }
}

/** 单选菜单浮层：选中项品牌色 + 勾。 */
@Composable
fun DshOptionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    options: List<Pair<String, String>>,
    selectedId: String?,
    onSelect: (label: String, id: String) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = Dsh.bgCard,
        shape = RoundedCornerShape(DshRadius.container),
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        offset = DpOffset(0.dp, 4.dp),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 180.dp, max = 260.dp)
                .padding(vertical = 4.dp),
        ) {
            options.forEach { (label, id) ->
                val selected = id == selectedId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = selected, role = Role.RadioButton) { onSelect(label, id) }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        color = if (selected) Dsh.brand400 else Dsh.labelPrimary,
                        style = DshType.bodyLarge,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(CheckOutline16, contentDescription = null, tint = Dsh.brand400, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/** 按钮行：整行一个文字操作（品牌色 / 危险红），不带箭头——对照 lody 的 plain Button。 */
@Composable
fun DshListActionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val color = when {
        !enabled -> Dsh.labelTertiary
        destructive -> Dsh.error
        else -> Dsh.brand400
    }
    Row(
        modifier = modifier
            .dividerInset(icon != null)
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = dshRipple(),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = RowPaddingH, vertical = RowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(IconSlot), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(IconSize))
            }
            Spacer(Modifier.width(IconGap))
        }
        Text(label, color = color, style = DshType.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

/**
 * 卡片里的说明 / 状态文字（加载中、空态、错误）：占一行，不可点。
 * [inset] 为真时与带图标行的文字起点对齐——用在展开的子行里，保持层级。
 */
@Composable
fun DshListNote(
    text: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    inset: Boolean = false,
) {
    Text(
        text,
        color = if (error) Dsh.error else Dsh.labelTertiary,
        style = DshType.body,
        modifier = modifier
            .dividerInset(inset)
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .padding(start = if (inset) TextInsetWithIcon else RowPaddingH, end = RowPaddingH, top = 14.dp, bottom = 14.dp),
    )
}

/** 卡片外的说明文字（页首导语），与分组页脚同一字阶。 */
@Composable
fun DshListCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Dsh.labelTertiary,
        style = DshType.captionRelaxed,
        modifier = modifier.padding(start = RowPaddingH, end = RowPaddingH, top = 12.dp),
    )
}

/** 卡片里的「加载失败 + 重试」：两行，放在 [DshListSection] 内。 */
@Composable
fun DshListRetry(message: String, onRetry: () -> Unit) {
    DshListNote(message, error = true)
    DshListActionRow(label = DshS.retry, onClick = onRetry)
}

/**
 * 页面大标题（迁移期兼容包装）：内部就是统一页面标题角色
 * （[DshType.headlineMedium]，docs/visual-rules.md 第四节）。
 * 调用点清零后删除——新页面一律用 [DshPageScaffold] 的标题。
 */
@Composable
fun DshLargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
        Text(
            title,
            color = Dsh.labelPrimary,
            style = DshType.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Dsh.labelTertiary, style = DshType.body)
        }
    }
}
