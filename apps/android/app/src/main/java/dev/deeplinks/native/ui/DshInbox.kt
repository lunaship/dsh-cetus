package dev.deeplinks.native.ui

import dev.deeplinks.native.DshIconSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.dshRipple
import dev.deeplinks.native.DshRadius
import dev.deeplinks.native.DshSpace

/**
 * 首页收件箱（inbox）与对话页共用的共享积木（2026-09-28 重设计 · 方案阶段 1）。
 *
 * 为什么单独一件：重设计稿把「墨色实心胶囊」「暖色状态胶囊」「白色分组卡」当成
 * 三种固定语义，而不是每页各画一遍形状。**胶囊形状只允许出现在共享组件里**
 * （ComponentLanguageTest），所以这些组件必须落在 `native/ui/`。
 *
 * 颜色一律走 [Dsh] 角色；间距一律走 [DshSpace]；圆角只用六个用途角色。
 * 深浅色、纯黑、动态取色、高对比都由角色自动跟随，组件本身不判断 isDark。
 */

// ============================================================
// DshPillButton —— 实心/墨色/tonal 三种胶囊按钮
// 视觉高 44dp、热区 48dp（方案 2.1 第 5 条）
// ============================================================

/** 胶囊按钮的三种语义。强调色只给「需要你动手」的动作（批准、发送）。 */
enum class DshPillTone {
    /** 品牌蓝实心：批准、发送这类主操作。 */
    Accent,

    /** 过程控制：新任务、停止。底色 inkFill，浅色墨色、深色深灰。 */
    Ink,

    /** Tonal：次要动作（拒绝、取消）。 */
    Tonal,

    /**
     * 描边：需要确认但不应被视觉引导的动作（例如看不到参数时的批准）。
     * 透明底 + labelPrimary 字 + 1dp 描边，与「拒绝」同权重，避免用户被主色牵着点。
     */
    Outline,
}

@Composable
fun DshPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: DshPillTone = DshPillTone.Accent,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 置灰全 App 一种写法：bgSubtle 底 + labelDimmed 字，不用透明度。
    val (container, content) = if (!enabled) {
        Dsh.bgSubtle to Dsh.labelDimmed
    } else when (tone) {
        DshPillTone.Accent -> Dsh.brand500 to Dsh.onBrand
        DshPillTone.Ink -> Dsh.inkFill to Dsh.onInk
        DshPillTone.Tonal -> Dsh.bgSubtle to Dsh.labelPrimary
        DshPillTone.Outline -> Color.Transparent to Dsh.labelPrimary
    }
    val bg = if (enabled && pressed) container.copy(alpha = 0.88f) else container
    // 与 DshFilterChip 同一手法：热区 48dp 挂在外层、胶囊视觉 44dp 画在内层，
    // 点按面积不缩，观感收紧（方案 2.1 第 5 条）。
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(
                interactionSource = interaction,
                indication = dshRipple(),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(DshRadius.full))
                .background(bg)
                .then(
                    if (enabled && tone == DshPillTone.Outline) {
                        Modifier.border(1.dp, Dsh.borderStrong, RoundedCornerShape(DshRadius.full))
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = DshSpace.s16),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(DshIconSize.md))
                Spacer(Modifier.width(DshSpace.s8))
            }
            Text(label, color = content, style = DshType.title, maxLines = 1)
        }
    }
}

// ============================================================
// DshStatusChip —— 会话状态胶囊（等你批准 / 等你回答 / 在电脑上处理 / 完成）
// 状态必须「颜色 + 文字」双通道，颜色只是第二通道
// ============================================================

enum class DshChipTone {
    /** 等你批准：6dp 琥珀点 + warnLabel 文字，不铺底。 */
    Approval,

    /** 等你回答：与批准同一种点，靠文字区分。 */
    Answer,

    /** 在电脑上处理、以及中性结果：bgSubtle 胶囊 + labelSecondary。 */
    Remote,

    /** 完成：与 Remote 同一套中性胶囊。完成的颜色在行首 DshStatusIcon 上。 */
    Done,
}

@Composable
fun DshStatusChip(
    text: String,
    tone: DshChipTone,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    leadingTint: Color = Dsh.labelSecondary,
) {
    if (tone == DshChipTone.Approval || tone == DshChipTone.Answer) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DshSpace.s6),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Dsh.warn),
            )
            Text(text, color = Dsh.warnLabel, style = DshType.captionMedium, maxLines = 1)
        }
        return
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(DshRadius.full))
            .background(Dsh.bgSubtle)
            .padding(horizontal = DshSpace.s8, vertical = DshSpace.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = leadingTint, modifier = Modifier.size(DshIconSize.sm))
            Spacer(Modifier.width(DshSpace.s6))
        }
        Text(text, color = Dsh.labelSecondary, style = DshType.captionMedium, maxLines = 1)
    }
}

// ============================================================
// DshStatusIcon —— 32dp 状态图标圈（列表行首）
// 完成 = 中性底 + successContent 图标；已停止 = 灰底方块；失败 = 错误色
// ============================================================

@Composable
fun DshStatusIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    container: Color = Dsh.bgSubtle,
    content: Color = Dsh.successContent,
    size: Dp = 32.dp,
    iconSize: Dp = 18.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(iconSize))
    }
}

// ============================================================
// DshGroupCard —— 白色分组卡（方案 2.3：圆角 composer、内边距 16）
// 分层靠 tonal（灰底 + 白卡），不加 1dp 描边（SurfaceHierarchyTest）
// ============================================================

@Composable
fun DshGroupCard(
    modifier: Modifier = Modifier,
    /** 内容内边距。首页卡片传 [DshSpace.s12]，让卡内文字与会话行标题落在同一条左边线（V2）。 */
    contentPadding: PaddingValues = PaddingValues(DshSpace.s16),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.composer))
            .background(Dsh.bgCard)
            .padding(contentPadding),
        content = content,
    )
}

/** 分组卡内的分隔线：只画一根发丝线，左侧按行首内容缩进。 */
@Composable
fun DshCardDivider(
    modifier: Modifier = Modifier,
    leadingInset: Dp = 60.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = leadingInset)
            .height(1.dp)
            .background(Dsh.borderSubtle),
    )
}

// ============================================================
// DshSectionLabel —— 分组标签（13 / Medium / 次要色）
// 只有灰字，不挂计数（visual-rules 第五节）
// ============================================================

@Composable
fun DshSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.padding(start = DshSpace.s4),
        color = Dsh.labelSecondary,
        style = DshType.titleSmall,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
    )
}

// ============================================================
// DshFloatingPill —— 页面底部居中的悬浮主按钮（「+ 新任务」）
// 全 App 唯一带阴影的普通按钮，语义同 FAB（visual-rules 第二节的浮层例外）
// ============================================================

@Composable
fun DshFloatingPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    // 启用 = inkFill / onInk；置灰 = bgSubtle + labelDimmed，与 DshPillButton 同一种写法。
    val container = if (enabled) Dsh.inkFill else Dsh.bgSubtle
    val content = if (enabled) Dsh.onInk else Dsh.labelDimmed
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = dshRipple(),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        // 阴影走 M3 Surface 的 shadowElevation（浮层语义），不用 Modifier.shadow：
        // 后者在 DshSurfaceRoleTest 里按文件计入「行内阴影」预算，而 FAB 属于允许的浮层。
        Surface(
            shape = RoundedCornerShape(DshRadius.full),
            color = container,
            shadowElevation = if (enabled) 4.dp else 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .height(52.dp)
                    .padding(start = DshSpace.s20, end = DshSpace.s24),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(DshIconSize.md))
                    Spacer(Modifier.width(DshSpace.s8))
                }
                Text(label, color = content, style = DshType.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}
