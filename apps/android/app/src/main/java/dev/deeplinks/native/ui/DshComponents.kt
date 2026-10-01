package dev.deeplinks.native.ui

import androidx.compose.runtime.getValue
import dev.deeplinks.core.tabularNums
import dev.deeplinks.core.DshType

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.deeplinks.core.dshRipple
import dev.deeplinks.core.readableTextColor
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.native.CheckOutline16
import dev.deeplinks.native.WarningOutline16
import dev.deeplinks.native.DshIconSize
import dev.deeplinks.native.DshRadius
import dev.deeplinks.native.DshSpace
import dev.deeplinks.native.DshTouch
import dev.deeplinks.native.motionDuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.selection.selectableGroup

/**
 * DSH 设计系统语义组件（WI-005 / WI-006）—— 通用 filter chip、tag、badge、banner。
 * 所有 token 均来自 [Dsh]（`LocalDshColors`），深浅色自动适配。
 * 每个组件都内建 TalkBack 语义（contentDescription / role），不再依赖外部 label。
 */

// ============================================================
// DshFilterChip —— 通用筛选/分段胶囊
// 语义角色：Button；选中状态通过 [selected] 控制
// ============================================================
@Composable
fun DshFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    enabled: Boolean = true,
    contentDescription: String? = null,
    /** 长按 extras（如工作区胶囊的「新建会话 / 移除」菜单）；为空时退化为普通点击。 */
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    // 选中是浅灰底上的深字。品牌蓝不进筛选。按压反馈只留水波纹（P1：去掉手动叠底）
    val bg = when {
        !enabled -> Color.Transparent
        selected -> Dsh.bgSubtle
        else -> Color.Transparent
    }
    val textColor = when {
        !enabled -> Dsh.labelDimmed
        selected -> Dsh.labelPrimary
        else -> Dsh.labelSecondary
    }
    // 视觉 32dp 胶囊 / 外层 48dp 触摸热区：可点面积不缩，观感收紧
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = dshRipple(),
                        enabled = enabled,
                        role = Role.Tab,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier.selectable(
                        selected = selected,
                        interactionSource = interaction,
                        indication = dshRipple(),
                        enabled = enabled,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                },
            )
            .semantics {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(DshRadius.full))
                .background(bg)
                .padding(horizontal = DshSpace.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color = textColor,
                style = DshType.title,
                fontWeight = FontWeight(500),
                lineHeight = 20.sp,
            )
            if (count != null) {
                Spacer(Modifier.width(DshSpace.s4))
                Text(
                    count.toString(),
                    color = if (selected) Dsh.labelSecondary else Dsh.labelTertiary,
                    style = DshType.captionRelaxed.tabularNums(),
                )
            }
        }
    }
}

// ============================================================
// DshSheetGrabber —— 底部弹层拖拽指示条（自绘 dragHandle=null 时的统一把手）
// ============================================================

// ---------- 顶栏胶囊分段（对话 / 轨迹） ----------

/** 胶囊基准尺寸（fontScale 1.0）：轨道 30dp、药丸 26dp、四边内缩 2dp；实际高度跟字号缩放。 */
private val TopSegmentTrackHeight = 30.dp
private val TopSegmentPillHeight = 26.dp
private val TopSegmentInset = 2.dp

/**
 * 单行顶栏的胶囊分段：一条圆角轨道 + 选中段上的药丸。
 *
 * 尺寸是「合适的胶囊」而不是把热区当轨道：轨道视觉 30dp、药丸 26dp，
 * 触控热区仍是整段 48dp（M3 下限）——上一版把 48dp 直接画成轨道，
 * 整块控件被撑到 52dp 高，观感笨重。
 *
 * 药丸贴合各自文字宽度（不做等分），选中/取消是 100ms 的短交叉淡入，
 * 文字本身不位移；药丸由每段自绘，不依赖跨段测量，首帧（含截图基线）就正确。
 * 选中态再叠字重，弱视/动态取色下也能分辨。
 */
@Composable
// ---------- 文字标签页（无底框） ----------

/**
 * 文字标签页：纯文字 + 选中下划线，无底框、无药丸。
 * 比胶囊 / 分段控件更安静——适合设置分区这类次要位置。
 * 视觉：选中 labelPrimary + Medium + 品牌色 2dp 下划线；未选中 labelTertiary + Normal。
 */
fun DshTextTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) return
    val safeIndex = selectedIndex.coerceIn(0, labels.lastIndex)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(DshSpace.s6)) {
        labels.forEachIndexed { index, label ->
            val selected = index == safeIndex
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    // M3 触控目标 ≥48dp（原 44dp 不达标）
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(DshRadius.control))
                    .clickable(
                        interactionSource = interaction,
                        indication = dshRipple(),
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    )
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    // IntrinsicSize.Max：让下划线的 fillMaxWidth 恰好等于单行文字宽度（Min 会取到单字、Max 外溢会撑满整行）
                    modifier = Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        label,
                        color = if (selected) dev.deeplinks.core.Dsh.labelPrimary else dev.deeplinks.core.Dsh.labelTertiary,
                        style = DshType.body,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        letterSpacing = 0.sp,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(3.dp))
                    // 下划线：宽度跟随文字（Column 宽 = 文字宽），未选中时透明避免跳动
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(RoundedCornerShape(DshRadius.full))
                            .background(if (selected) dev.deeplinks.core.Dsh.labelPrimary else Color.Transparent),
                    )
                }
            }
        }
    }
}

/**
 * 顶栏用紧凑二段切换：视觉 32dp 高，触控 ≥48dp。
 * 选中态颜色切换遵守减弱动画设置。
 */
@Composable
fun DshSegmentedToggle(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<ImageVector>? = null,
) {
    if (labels.isEmpty()) return
    val safeIndex = selectedIndex.coerceIn(0, labels.lastIndex)
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(DshRadius.full))
            .background(Dsh.bgSubtle)
            .padding(DshSpace.s2)
            .selectableGroup(),
    ) {
        labels.forEachIndexed { i, label ->
            val selected = i == safeIndex
            // 每段各自按选中态取色（此前整组共用一个永远为真的判断，未选中段不会变灰）
            val segmentColor by animateColorAsState(
                targetValue = if (selected) Dsh.labelPrimary else Dsh.labelTertiary,
                animationSpec = tween(motionDuration(150)),
                label = "segment-$i",
            )
            val segmentBg by animateColorAsState(
                targetValue = if (selected) Dsh.bgCard else Color.Transparent,
                animationSpec = tween(motionDuration(150)),
                label = "segment-bg-$i",
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(DshRadius.full))
                    .background(segmentBg)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(i) })
                    .padding(horizontal = DshSpace.s12),
                contentAlignment = Alignment.Center,
            ) {
                val icon = icons?.getOrNull(i)
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = label,
                        tint = segmentColor,
                        modifier = Modifier.size(DshIconSize.sm),
                    )
                } else {
                    Text(
                        label,
                        style = DshType.caption,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = segmentColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
fun DshSheetGrabber() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = DshSpace.s8, bottom = DshSpace.s4),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(Dsh.borderStrong),
        )
    }
}

// ============================================================
// DshTag —— 小型语义标签（轨迹视图 chip / 会话元数据 / 分类标签）
// 语义角色：默认 None；可由 contentDescription 覆盖
// ============================================================
@Composable
fun DshTag(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Dsh.bgSubtle,
    contentColor: Color = Dsh.labelSecondary,
    shape: Shape = RoundedCornerShape(DshRadius.control),
    contentDescription: String? = null,
) {
    val mod = modifier
        .clip(shape)
        .background(color)
        .padding(horizontal = DshSpace.s8, vertical = DshSpace.s2)
        .semantics {
            if (contentDescription != null) this.contentDescription = contentDescription
        }
    Text(
        text = text,
        color = contentColor,
        style = DshType.microRelaxed,
        fontWeight = FontWeight(500),
        modifier = mod,
    )
}

// ============================================================
// DshBadge —— 圆点 / 数字徽章（导航项、状态点）
// 语义角色：默认 None（用于装饰时）；count 形式宣读为 "X"
// ============================================================
@Composable
fun DshBadge(
    modifier: Modifier = Modifier,
    color: Color = Dsh.error,
    contentColor: Color = Dsh.onBrand,
    count: Int? = null,
    dot: Boolean = false,
    contentDescription: String? = null,
) {
    val s = DshS
    if (dot && (count == null || count <= 0)) {
        Box(
            modifier = modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
                .semantics {
                    this.contentDescription = contentDescription ?: s.statusDot
                }
        )
        return
    }
    val showCount = count != null && count > 0
    val label = when {
        showCount -> if (count > 99) "99+" else count.toString()
        else -> ""
    }
    val fallbackDescription = if (showCount) s.unreadCount.format(label) else s.statusDot
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(DshRadius.full))
            .background(color)
            .padding(horizontal = if (showCount) 6.dp else 0.dp, vertical = if (showCount) 2.dp else 0.dp)
            .semantics {
                this.contentDescription = contentDescription ?: fallbackDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        if (showCount) {
            Text(
                text = label,
                // 徽章底色任意（默认 error）：内容色朝底色的高对比侧收敛，保证 AA
                color = readableTextColor(contentColor, listOf(color)),
                style = DshType.label,
                lineHeight = 14.sp,
                fontWeight = FontWeight(600),
            )
        }
    }
}

@Composable
fun DshBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: DshBannerTone = DshBannerTone.Info,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    contentDescription: String? = null,
) {
    val (bg, fg, accent) = when (tone) {
        // Info 用 bgSubtle：浅色 bgCard 与白画布同色，横幅会整块隐形（暗色下却是一张卡）。
        DshBannerTone.Info -> Triple(Dsh.bgSubtle, Dsh.labelSecondary, Dsh.brand400)
        // 警告 / 成功：中性底 + 左侧图标。语义色只在图标上，文字用 labelPrimary。错误横幅保留 errorBg。
        DshBannerTone.Warn -> Triple(Dsh.bgSubtle, Dsh.labelPrimary, Dsh.labelPrimary)
        DshBannerTone.Error -> Triple(Dsh.errorBg, Dsh.error, Dsh.error)
        DshBannerTone.Success -> Triple(Dsh.bgSubtle, Dsh.labelPrimary, Dsh.labelPrimary)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.container))
            .background(bg)
            .padding(horizontal = DshSpace.s12, vertical = DshSpace.s8)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                if (contentDescription != null) this.contentDescription = contentDescription
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 语义色只做图标，不加单侧色边。调用方传入 leading 时用调用方的。
        if (leading != null) {
            leading()
            Spacer(Modifier.width(DshSpace.s8))
        } else if (tone == DshBannerTone.Warn) {
            Icon(WarningOutline16, contentDescription = null, tint = Dsh.warn, modifier = Modifier.size(DshIconSize.md))
            Spacer(Modifier.width(DshSpace.s8))
        } else if (tone == DshBannerTone.Success) {
            Icon(CheckOutline16, contentDescription = null, tint = Dsh.successContent, modifier = Modifier.size(DshIconSize.md))
            Spacer(Modifier.width(DshSpace.s8))
        }
        Text(
            text = text,
            color = fg,
            style = DshType.body,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(DshSpace.s8))
            BannerTextButton(actionLabel, accent, onAction)
        }
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            Spacer(Modifier.width(DshSpace.s8))
            BannerTextButton(secondaryActionLabel, accent, onSecondaryAction)
        }
    }
}

enum class DshBannerTone { Info, Warn, Error, Success }

@Composable
private fun BannerTextButton(label: String, color: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(DshRadius.control))
            .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onClick)
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .padding(horizontal = DshSpace.s8, vertical = DshSpace.s4)
            .semantics {
                role = Role.Button
                this.contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = color,
            style = DshType.body,
            fontWeight = FontWeight(500),
        )
    }
}

// ============================================================
// DshBanner —— 横条提示（断线横幅、审批等待、状态广播）
// 语义角色：默认无（仅公告）；可选 onAction 时宣读动作
// ============================================================
@Composable
fun ChatLoadingSkeleton(
    modifier: Modifier = Modifier,
    lineCount: Int = 4,
    contentDescription: String? = null,
) {
    val loadingLabel = contentDescription ?: DshS.loading
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpace.s16, vertical = DshSpace.s12)
            .semantics { this.contentDescription = loadingLabel },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 助手文本行（宽，左对齐）
        repeat(lineCount) { idx ->
            val widthFrac = when (idx % 4) {
                0 -> 0.85f
                1 -> 0.65f
                2 -> 0.78f
                else -> 0.45f
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth(widthFrac)
                    .height(14.dp)
                    .clip(RoundedCornerShape(DshRadius.control))
                    .background(Dsh.bgSubtle)
            )
        }
        // 用户气泡（短，右对齐）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(DshRadius.container))
                    .background(Dsh.bgSubtle)
            )
        }
        // 代码块占位（mono 字号）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(DshRadius.container))
                .background(Dsh.bgCode)
        )
    }
    // 默认内容色：默认即可（每个占位都有自己的颜色）
    CompositionLocalProvider(LocalContentColor provides Dsh.labelPrimary) {}
}

// ============================================================
// DshHeaderAction —— 卡片/弹窗头部的小号文字动作（复制 / 下载 / 关闭…）
// 语义角色：Button；原三份私有副本（Mermaid / Table / SelectText）合并至此
// ============================================================
@Composable
fun DshHeaderAction(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(DshRadius.control))
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onClick)
            .padding(horizontal = DshSpace.s8),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Dsh.labelTertiary, style = DshType.microRelaxed,)
    }
}

// ============================================================
// DshIconAction —— 页面/卡片内的图标按钮（统一 48dp 热区与按压态）
// 语义角色：Button；顶栏、Section 头、列表行尾的图标动作共用这一件
// ============================================================
@Composable
fun DshIconAction(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 触控热区：不低于 [DshTouch.min]（48dp）。 */
    size: Dp = DshTouch.min,
    iconSize: Dp = DshIconSize.md,
    /**
     * 视觉圆底直径，默认与热区相同。要「视觉更小、热区仍 48」时只传它
     * （例如顶栏 40dp 圆底、48dp 热区），不要缩小 [size]。
     */
    visualSize: Dp = size,
    active: Boolean = false,
    tint: Color = Dsh.labelSecondary,
    /** 实心模式（如任务入口的 + 钮）：容器用品牌色，图标用 onBrand。 */
    containerColor: Color? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(size)
            .semantics {
                role = Role.Button
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .clickable(interactionSource = interaction, indication = dshRipple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(visualSize)
                .clip(CircleShape)
                .background(
                    when {
                        containerColor != null -> containerColor
                        // 激活态靠底色表达，强调色只给批准 / 发送（V3）；按压只留水波纹（P1）
                        active -> Dsh.bgNavSelected
                        else -> Color.Transparent
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (containerColor != null) Dsh.onBrand else if (active) Dsh.labelPrimary else tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

// ============================================================
// DshPrimaryAction —— 实心主操作（每个表面最多一个）
// 语义角色：Button；品牌蓝实心 + 全圆。禁用 = bgSubtle 底 + labelDimmed 字，与其它按钮同一种写法。
// ============================================================
@Composable
fun DshPrimaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    danger: Boolean = false,
) {
    val container = if (!enabled) Dsh.bgSubtle else if (danger) Dsh.error else Dsh.brand500
    val content = if (!enabled) Dsh.labelDimmed else Dsh.onBrand
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(DshRadius.full))
            .background(container)
            .clickable(
                interactionSource = interaction,
                indication = dshRipple(),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = DshSpace.s24, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(DshIconSize.md))
            Spacer(Modifier.width(DshSpace.s8))
        }
        Text(label, color = content, style = DshType.labelLarge, maxLines = 1)
    }
}

// ============================================================
// DshStatusBadge —— 状态 pill（等待 / 运行 / 成功 / 错误 / 中性）
// 等待 / 运行 / 成功：bgSubtle 底，靠 6dp 色点（warn / brand400 / successContent）和对应文字色区分。
// 错误保留 errorBg。不铺语义色底，不加单侧色边。
// ============================================================
enum class DshStatusTone { Neutral, Waiting, Running, Success, Error }

@Composable
fun DshStatusBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: DshStatusTone = DshStatusTone.Neutral,
    dot: Boolean = false,
    contentDescription: String? = null,
) {
    val (bg, fg, accent) = when (tone) {
        DshStatusTone.Neutral -> Triple(Dsh.bgSubtle, Dsh.labelSecondary, Dsh.labelTertiary)
        DshStatusTone.Waiting -> Triple(Dsh.bgSubtle, Dsh.warnLabel, Dsh.warn)
        DshStatusTone.Running -> Triple(Dsh.bgSubtle, Dsh.brand400, Dsh.brand400)
        DshStatusTone.Success -> Triple(Dsh.bgSubtle, Dsh.successContent, Dsh.successContent)
        DshStatusTone.Error -> Triple(Dsh.errorBg, Dsh.error, Dsh.error)
    }
    val showDot = dot || tone == DshStatusTone.Waiting || tone == DshStatusTone.Running || tone == DshStatusTone.Success
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(DshRadius.full))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showDot) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Spacer(Modifier.width(DshSpace.s6))
        }
        Text(text, color = fg, style = DshType.captionRelaxed, maxLines = 1)
    }
}

/**
 * E4：主机在线状态点。首页顶栏、设置页已配对电脑行、设备页三处共用同一个组件
 * 与同一组颜色 token——此前首页/设备页用绿色实心点，设置页把「●」当成文字 glyph、
 * 颜色跟随 value 文字色（深灰），同一状态三处不一致。
 *
 * 颜色：在线 = 成功色 [Dsh.successContent]；离线 = [Dsh.labelTertiary]。
 * null = 还没探到，不画点（由调用方决定是否显示状态文字）。
 */
@Composable
fun HostStatusDot(online: Boolean?, size: Dp = 7.dp) {
    if (online == null) return
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (online) Dsh.successContent else Dsh.labelTertiary),
    )
}
