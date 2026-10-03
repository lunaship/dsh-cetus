package dev.deeplinks.native.ui

import dev.deeplinks.native.DshIconSize
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
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
import dev.deeplinks.native.CheckOutline16
import dev.deeplinks.native.ChevronDownOutline16
import dev.deeplinks.native.ChevronRightOutline16
import dev.deeplinks.native.DshRadius
import dev.deeplinks.native.DshSpace
import dev.deeplinks.native.DshTouch

/**
 * Section 与行骨架（v4：docs/redesign-v4/visual-rules-v4.md §4，设置、设备复用同一行结构）。
 *
 * 一页 = 画布底 + 若干 [DshSection]。Section 一律平铺：行直接落在画布上，不画行间线，
 * 靠组头分组；只有独立数据块（当前电脑）用 **Tonal**（surface1 容器色块 + container 圆角）。
 * 行只有一种骨架 [DshListRow]：图标 · 标题 / 副标题 · 取值 · 尾标，开关、下拉、按钮行都是它的变体。
 * 行首图标默认次要色，危险行强制 err。
 */

private val RowPaddingH = DshSpace.s20
private val RowPaddingV = 12.dp
private val IconSlot = DshIconSize.lg
/** 自有图标是满幅绘制（无内边距），18dp 与原先 22dp 的 Material 图标视觉等大。 */
private val IconSize = DshIconSize.md
/**
 * 图标槽与文字的间距。
 * 2026-09-28 重设计稿的列表行是 `16px 边距 + 32px 图标圈 + 12px 间距`（文字起点 60），
 * 取 s12 后 16+32+12 = 60 与稿子一致；原先的 14 是刻度外的存量（方案 2.3）。
 */
private val IconGap = DshSpace.s16
/**
 * 单行最小高。
 * 方案 2.3 规定「设置行、列表行最小 52」，2026-09-28 重设计把原先的 48 提到 52。
 */
private val RowMinHeight = 52.dp
/** 右侧取值的最大宽度：取值贴右、尾标成一条竖线；超长时截断取值而不是挤压标题。 */
private val ValueMaxWidth = 168.dp
private val TextInsetWithIcon = RowPaddingH + IconSlot + IconGap
/** 两行（有副标题）行的最小高（v4 §4）。 */
private val RowMinHeightDouble = 64.dp

private data class DividerInset(val start: Dp) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@DividerInset
}

/** 行的根节点声明分隔线起点；卡片用它画「上一行与本行之间」的那根线。 */
private fun Modifier.dividerInset(hasIcon: Boolean, iconSlot: Dp = IconSlot): Modifier =
    then(DividerInset(if (hasIcon) RowPaddingH + iconSlot + IconGap else RowPaddingH))

/** Section 容器策略：默认扁平；tonal 必须有独立分组理由（总结 / 警告 / 独立数据块）。 */
enum class DshSectionContainer {
    /** 行直接落在画布上，行间发丝线分组。 */
    Flat,

    /** 容器色块（surface1 + container 圆角）：当前电脑这类独立数据块。 */
    Tonal,

    /** 兼容旧调用：v4 与 [Flat] 相同（不再画白卡）。 */
    Card,
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
    Column(modifier = modifier.fillMaxWidth().padding(top = if (header != null) 0.dp else DshSpace.s8)) {
        if (header != null) {
            DshSectionHeader(
                title = header,
                actionLabel = headerAction,
                actionDanger = headerActionDanger,
                actionEnabled = headerActionEnabled,
                onAction = onHeaderAction,
            )
        }
        DshSectionRows(container = container, content = content)
        if (footer != null) {
            // 4.2：长说明首页只留一行灰字摘要，点按展开完整段落（不再整段铺在页面上）
            var footerExpanded by remember(footer) { mutableStateOf(false) }
            val collapsible = footer.length > FOOTER_COLLAPSE_LIMIT
            Text(
                footer,
                color = Dsh.tertiaryText,
                style = DshType.supporting,
                maxLines = if (collapsible && !footerExpanded) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = RowPaddingH, end = RowPaddingH, top = DshSpace.s4, bottom = DshSpace.s8)
                    .then(
                        if (collapsible) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = dshRipple(),
                                role = Role.Button,
                                onClick = { footerExpanded = !footerExpanded },
                            )
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

/** 页脚超过这个字符数就按「一行摘要 + 点按展开」渲染（4.2 长说明收纳）。 */
private const val FOOTER_COLLAPSE_LIMIT = 48

/**
 * Section（迁移期兼容包装）：与 [DshSection] 同一件，[container] 直通容器策略
 * （2026-10-02 Lody 简化：设置 / 设备页统一传 Card）；[tonal] 为兼容旧调用保留。
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
    container: DshSectionContainer? = null,
    content: @Composable () -> Unit,
) {
    DshSection(
        modifier = modifier,
        header = header,
        footer = footer,
        container = container ?: if (tonal) DshSectionContainer.Tonal else DshSectionContainer.Flat,
        headerAction = headerAction,
        headerActionDanger = headerActionDanger,
        headerActionEnabled = headerActionEnabled,
        onHeaderAction = onHeaderAction,
        content = content,
    )
}

/**
 * Section 标题（小号灰字，与行内文字对齐）；Section 外的自定义内容也用它起头。
 * [contentStart] 默认对齐分组行的文字；行距不同的列表（如任务首页）传 0 自己对齐。
 */
@Composable
fun DshSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actionDanger: Boolean = false,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
    contentStart: Dp = RowPaddingH,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DshTouch.min)
            .padding(start = contentStart, end = DshSpace.s8, top = DshSpace.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // v4 组头（同 DlSectionHeader）：13sp 600 次要色
        Text(
            title,
            color = Dsh.labelSecondary,
            style = DshType.supporting.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                enabled = actionEnabled,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (actionDanger) Dsh.err else Dsh.brand400,
                    disabledContentColor = Dsh.tertiaryText,
                ),
            ) {
                Text(actionLabel, style = DshType.supporting.copy(fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

/**
 * Section 行容器：自排版子节点（0 高度的节点——例如弹层锚点——不占位）。
 * [container] = Tonal 时加 surface1 底 + container 圆角；其余行直接落在画布上。
 */
@Composable
private fun DshSectionRows(
    container: DshSectionContainer,
    content: @Composable () -> Unit,
) {
    // v4：平铺列表不画行间线；只有 Tonal（当前电脑这种独立数据块）是容器色块
    val dividerColor = Color.Transparent
    val boundaries = remember { mutableListOf<Pair<Float, Float>>() }
    val surface = when (container) {
        DshSectionContainer.Tonal -> Modifier
            .padding(horizontal = DshSpace.s12)
            .clip(RoundedCornerShape(DshRadius.container))
            .background(Dsh.surface1)
        DshSectionContainer.Card, DshSectionContainer.Flat -> Modifier
    }
    Layout(
        content = content,
        modifier = Modifier
            .fillMaxWidth()
            .then(surface)
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
 * 白色分组卡行容器（[DshSectionContainer.Card] 的直接形态，2026-10-02 Lody 简化 3.1）：
 * 首页等自排行页面把行作直接子节点放进来——bgCard + card 20dp 圆角由这里负责，
 * 行间发丝线自动画（未声明分隔线起点的子节点默认从 16dp 文字起点起算）。
 */
@Composable
fun DshCardRows(content: @Composable () -> Unit) {
    DshSectionRows(container = DshSectionContainer.Card, content = content)
}

/**
 * 分组行骨架。[onClick] 为空即只读行；[value] 是右侧当前值（灰字）；
 * [error] 显示在副标题下方，带 [onRetry] 时给出行内重试。
 */
@Composable
fun DshListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /** 副标题用等宽字体（方案 7：电脑卡的地址要等宽，让 IP 与端口对齐好读）。 */
    subtitleMono: Boolean = false,
    icon: ImageVector? = null,
    // 行首线性图标默认次要色（v4）；危险行仍强制 err。
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
    /**
     * 行首图标槽宽（含 `leading` 槽）。
     * 默认 22dp 是设置/设备行的紧凑规格；收件箱行传 32dp——重设计稿的列表行首是
     * 32dp 状态圈（方案阶段 1），分隔线缩进随之变成 16+32+12 = 60，与稿子一致。
     */
    iconSlot: Dp = IconSlot,
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
        modifier = modifier.dividerInset(icon != null || leading != null, iconSlot).then(clickable),
        title = title,
        subtitle = subtitle,
        subtitleMono = subtitleMono,
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
        iconSlot = iconSlot,
    )
}

@Composable
private fun DshListRowLayout(
    modifier: Modifier,
    title: String,
    subtitle: String?,
    subtitleMono: Boolean = false,
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
    iconSlot: Dp = IconSlot,
) {
    val titleColor = when {
        !enabled -> Dsh.tertiaryText
        destructive -> Dsh.err
        else -> Dsh.labelPrimary
    }
    val hasLeading = leading != null || icon != null
    Column(modifier = modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (subtitle.isNullOrBlank()) RowMinHeight else RowMinHeightDouble)
            .padding(horizontal = RowPaddingH, vertical = RowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> {
                Box(Modifier.size(iconSlot), contentAlignment = Alignment.Center) { leading() }
                Spacer(Modifier.width(IconGap))
            }
            icon != null -> {
                Box(Modifier.size(iconSlot), contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = when {
                            !enabled -> Dsh.tertiaryText
                            destructive -> Dsh.err
                            else -> iconTint
                        },
                        modifier = Modifier.size(IconSize),
                    )
                }
                Spacer(Modifier.width(IconGap))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            // 标题一行：真机上见过主机名带换行（配对时输入的名字含 \n），两行会把整行撑开、
            // 把右侧状态挤走。设置页的电脑卡与列表行都靠这一条保持单行。
            // 16/22 Medium（2026-10-01 R7）：列表主标题角色集中映射，页面不临时改。
            Text(title, color = titleColor, style = DshType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    color = if (enabled) Dsh.labelSecondary else Dsh.tertiaryText,
                    style = if (subtitleMono) DshType.supporting.copy(fontFamily = FontFamily.Monospace) else DshType.supporting,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!value.isNullOrBlank()) {
            Spacer(Modifier.width(DshSpace.s12))
            Text(
                value,
                color = Dsh.labelSecondary,
                style = DshType.supporting,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = ValueMaxWidth),
            )
        }
        if (trailingContent != null) {
            Spacer(Modifier.width(DshSpace.s12))
            trailingContent()
        }
        DshListTrailingMark(trailing)
    }
        if (!error.isNullOrBlank()) {
            DshListRowError(
                error = error,
                onRetry = onRetry,
                modifier = Modifier.padding(
                    start = RowPaddingH + if (hasLeading) iconSlot + IconGap else 0.dp,
                    end = RowPaddingH,
                    bottom = DshSpace.s12,
                ),
            )
        }
    }
}

@Composable
private fun DshListTrailingMark(trailing: DshListTrailing) {
    val (icon, tint, size) = when (trailing) {
        DshListTrailing.None -> return
        DshListTrailing.Chevron -> Triple(ChevronRightOutline16, Dsh.tertiaryText, DshIconSize.sm)
        DshListTrailing.Check -> Triple(CheckOutline16, Dsh.brand400, DshIconSize.md)
        DshListTrailing.Select -> Triple(ChevronDownOutline16, Dsh.tertiaryText, DshIconSize.sm)
    }
    Spacer(Modifier.width(DshSpace.s8))
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

@Composable
private fun DshListRowError(error: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    val s = DshS
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            s.saveFailedWithMessage.format(error),
            color = Dsh.err,
            style = DshType.supporting,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            Box(
                modifier = Modifier
                    .heightIn(min = DshTouch.min)
                    .clip(RoundedCornerShape(DshRadius.control))
                    .clickable(role = Role.Button, onClick = onRetry)
                    .padding(horizontal = DshSpace.s8),
                contentAlignment = Alignment.Center,
            ) {
                Text(s.retry, color = Dsh.brand400, style = DshType.supporting.copy(fontWeight = FontWeight.SemiBold))
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
    // v4 开关（与 DlListRow 同一套）：开 = 品牌色轨 + onBrand 拇指；关 = surface2 轨 + 描边
    checkedThumbColor = Dsh.onBrand,
    checkedTrackColor = Dsh.brand400,
    checkedBorderColor = Dsh.brand400,
    uncheckedThumbColor = Dsh.tertiaryText,
    uncheckedTrackColor = Dsh.surface2,
    uncheckedBorderColor = Dsh.outline,
    disabledCheckedTrackColor = Dsh.surface2,
    disabledCheckedThumbColor = Dsh.tertiaryText,
    disabledUncheckedTrackColor = Dsh.surface1,
    disabledUncheckedThumbColor = Dsh.outline,
    disabledUncheckedBorderColor = Dsh.outline,
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
    description: String? = null,
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
                description = description,
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
    description: String? = null,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = Dsh.surface2,
        shape = RoundedCornerShape(DshRadius.container),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        offset = DpOffset(0.dp, DshSpace.s4),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 180.dp, max = 260.dp)
                .padding(vertical = DshSpace.s4),
        ) {
            if (description != null) {
                Text(
                    description,
                    color = Dsh.labelSecondary,
                    style = DshType.supporting,
                    modifier = Modifier.padding(horizontal = DshSpace.s16, vertical = DshSpace.s4),
                )
            }
            options.forEach { (label, id) ->
                val selected = id == selectedId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = selected, role = Role.RadioButton) { onSelect(label, id) }
                        .padding(horizontal = DshSpace.s16),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        color = if (selected) Dsh.brand400 else Dsh.labelPrimary,
                        style = DshType.body,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(CheckOutline16, contentDescription = null, tint = Dsh.brand400, modifier = Modifier.size(DshIconSize.md))
                    }
                }
            }
        }
    }
}

/** 按钮行：整行一个操作。普通操作跟正文同色（图标次要色），危险操作用红。 */
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
        !enabled -> Dsh.tertiaryText
        destructive -> Dsh.err
        else -> Dsh.labelPrimary
    }
    val iconColor = when {
        !enabled -> Dsh.tertiaryText
        destructive -> Dsh.err
        else -> Dsh.labelSecondary
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
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(IconSize))
            }
            Spacer(Modifier.width(IconGap))
        }
        Text(label, color = color, style = DshType.body, fontWeight = FontWeight.Medium)
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
    /** 可点备注（设置页页脚的「关于」）：给了就整行可点，仍保持备注的安静样式。 */
    onClick: (() -> Unit)? = null,
) {
    Text(
        text,
        color = if (error) Dsh.err else Dsh.labelSecondary,
        style = DshType.supporting,
        modifier = modifier.then(
            if (onClick == null) {
                Modifier
            } else {
                Modifier
                    .clip(RoundedCornerShape(DshRadius.control))
                    .clickable(interactionSource = null, indication = dshRipple(), onClick = onClick)
            },
        )
            .dividerInset(inset)
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .padding(start = if (inset) TextInsetWithIcon else RowPaddingH, end = RowPaddingH, top = DshSpace.s12, bottom = DshSpace.s12),
    )
}

/** 卡片外的说明文字（页首导语），与分组页脚同一字阶。 */
@Composable
fun DshListCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Dsh.labelSecondary,
        style = DshType.supporting,
        modifier = modifier.padding(start = RowPaddingH, end = RowPaddingH, top = DshSpace.s12),
    )
}

/** 卡片里的「加载失败 + 重试」：两行，放在 [DshListSection] 内。 */
@Composable
fun DshListRetry(message: String, onRetry: () -> Unit) {
    DshListNote(message, error = true)
    DshListActionRow(label = DshS.retry, onClick = onRetry)
}
