package dev.deeplinks.native.ui.v4

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.tabularNums
import dev.deeplinks.native.ArrowLeftOutline16
import dev.deeplinks.native.CloseOutline16
import dev.deeplinks.native.DshIconSize
import dev.deeplinks.native.DshSpace

/** 顶栏左侧：返回、关闭或不放。 */
enum class DlTopBarNav { Back, Close, None }

/** 顶栏右侧动作（最多 2 个）。 */
data class DlTopBarAction(
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    /** 开关类动作（如自动换行）：非 null 时按开 / 关着色并读出状态。 */
    val selected: Boolean? = null,
)

/** 对话页 diff 角标：`+n −m`。 */
data class DlDiffStat(val added: Int, val removed: Int, val onClick: () -> Unit)

/**
 * v4 顶栏（2.1、4.1、7.x）：返回 / 关闭 + 标题 + 可选副标题 + 最多 2 个动作，
 * 对话页可加 diff 角标。实底画布色，无阴影，滚动时不变色；需要分隔时传 [showDivider]。
 * [large] 是首页大标题（26sp，无返回键）。
 */
@Composable
fun DlTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    nav: DlTopBarNav = DlTopBarNav.Back,
    onNav: () -> Unit = {},
    actions: List<DlTopBarAction> = emptyList(),
    diff: DlDiffStat? = null,
    large: Boolean = false,
    showDivider: Boolean = false,
    subtitleContent: (@Composable () -> Unit)? = null,
    onSubtitleClick: (() -> Unit)? = null,
    /** 标题用等宽字（6.5 预览顶栏的地址）。 */
    monoTitle: Boolean = false,
) {
    require(actions.size <= 2) { "DlTopBar 最多 2 个动作" }
    Column(modifier.fillMaxWidth().background(Dsh.bgBase)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (large) DlSize.topBarLarge else DlSize.topBar)
                .padding(horizontal = DshSpace.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (nav) {
                DlTopBarNav.None -> Spacer(Modifier.width(DshSpace.s12))
                DlTopBarNav.Back, DlTopBarNav.Close -> DlIconButton(
                    icon = if (nav == DlTopBarNav.Back) ArrowLeftOutline16 else CloseOutline16,
                    contentDescription = if (nav == DlTopBarNav.Back) DshS.back else DshS.close,
                    onClick = onNav,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = DshSpace.s4),
            ) {
                Text(
                    title,
                    style = when {
                        large -> DshType.displayLarge
                        monoTitle -> DshType.titleLarge.copy(fontFamily = FontFamily.Monospace)
                        else -> DshType.titleLarge
                    },
                    color = Dsh.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                val subtitleModifier = if (onSubtitleClick != null) Modifier.clickable(onClick = onSubtitleClick) else Modifier
                when {
                    subtitleContent != null -> Row(subtitleModifier, verticalAlignment = Alignment.CenterVertically) { subtitleContent() }
                    subtitle != null -> Text(
                        subtitle,
                        style = DshType.supporting,
                        color = Dsh.labelSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = subtitleModifier,
                    )
                }
            }
            if (diff != null) DlDiffBadge(diff)
            for (action in actions) {
                DlIconButton(
                    action.icon,
                    action.contentDescription,
                    action.onClick,
                    enabled = action.enabled,
                    tint = if (action.selected == true) Dsh.brand400 else Dsh.labelPrimary,
                    selected = action.selected,
                )
            }
        }
        if (showDivider) HorizontalDivider(thickness = 1.dp, color = Dsh.outline)
    }
}

@Composable
internal fun DlIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color = Dsh.labelPrimary,
    selected: Boolean? = null,
) {
    val toggle = if (selected != null) {
        Modifier.semantics { stateDescription = if (selected) "on" else "off" }
    } else {
        Modifier
    }
    IconButton(onClick = onClick, enabled = enabled, modifier = toggle) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else Dsh.tertiaryText,
            modifier = Modifier.size(DshIconSize.md),
        )
    }
}

@Composable
private fun DlDiffBadge(diff: DlDiffStat) {
    val style = DlLabelStrong.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold).tabularNums()
    Row(
        modifier = Modifier
            .height(DlSize.chip)
            .background(Dsh.surface1, DlPill)
            .clickable(onClick = diff.onClick)
            .padding(horizontal = DshSpace.s12),
        horizontalArrangement = Arrangement.spacedBy(DshSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("+${diff.added}", style = style, color = Dsh.ok)
        Text("\u2212${diff.removed}", style = style, color = Dsh.err)
    }
}
