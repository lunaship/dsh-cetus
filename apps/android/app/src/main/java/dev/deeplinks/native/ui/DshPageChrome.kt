package dev.deeplinks.native.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.native.ArrowLeftOutline16
import dev.deeplinks.native.DshSpace

/**
 * 页面导航区两档密度（docs/visual-rules.md 第一节，2026-10-01 R2）：
 * - Standard：标准页（设置、设备、二级页）64dp，标题 headlineMedium 20/26；
 * - Compact：首页品牌顶栏与聊天顶栏 56dp，标题 titleLarge 17/24。
 *
 * 标题、副标题与页面动作由本组件统一布局，页面不得自定字号与顶栏高度；
 * 大字号允许增高（heightIn 下限，不裁字）。副标题 12/18，只放有用的电脑、
 * 工作区或状态信息。
 */

enum class DshPageChromeDensity(val minHeight: Dp) {
    Standard(64.dp),
    Compact(56.dp),
}

@Composable
fun DshPageChrome(
    title: String,
    density: DshPageChromeDensity,
    modifier: Modifier = Modifier,
    navigation: DshPageNavigation = DshPageNavigation.None,
    onNavigateBack: () -> Unit = {},
    /** 第二行：工作区 · 电脑名 / 执行状态 / 页面说明；只放有用的信息，不为填空重复品牌。 */
    subtitle: String? = null,
    /** 标题尾部小插槽（首页品牌状态点）；参与标题区的合并朗读。 */
    titleTrailing: (@Composable RowScope.() -> Unit)? = null,
    /** 覆盖标题区的合并朗读描述（首页「DeepLinks, 在线」）；null 时朗读标题文本本身。 */
    titleContentDescription: String? = null,
    /** 页面动作（搜索、设置、分段控件等），贴导航区行尾。 */
    actions: @Composable RowScope.() -> Unit = {},
    horizontalPadding: Dp = DshSpace.s4,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = density.minHeight)
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navigation == DshPageNavigation.Back) {
            DshIconAction(
                icon = ArrowLeftOutline16,
                contentDescription = DshS.back,
                onClick = onNavigateBack,
            )
            Spacer(Modifier.width(DshSpace.s4))
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {
                    heading()
                    if (titleContentDescription != null) contentDescription = titleContentDescription
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = Dsh.labelPrimary,
                    style = when (density) {
                        DshPageChromeDensity.Standard -> DshType.headlineMedium
                        DshPageChromeDensity.Compact -> DshType.titleLarge
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        color = Dsh.labelSecondary,
                        style = DshType.captionRelaxed,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (titleTrailing != null) {
                Spacer(Modifier.width(DshSpace.s8))
                titleTrailing()
            }
        }
        actions()
    }
}
