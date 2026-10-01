package dev.deeplinks.native.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.native.ArrowLeftOutline16
import dev.deeplinks.native.DshSpace
import dev.deeplinks.native.dshTranslucent

/**
 * 一级页面统一骨架（docs/visual-rules.md 第一节）。
 *
 * 页面不得自行决定标题、边距和背景：所有一级页面都是
 * 「页面导航区（返回/设备上下文 · 标题 · 页面动作）+ 页面内容」，
 * 画布固定 [Dsh.bgBase]，Compact 水平边距 16dp，Medium/Expanded 内容
 * 最大宽度 [maxContentWidth]（聊天工作区与双栏布局不受此限制）。
 */

/** 页面导航区左侧内容：无 / 返回按钮。 */
enum class DshPageNavigation {
    None,
    Back,
}

private val PageNavHeight = 56.dp

@Composable
fun DshPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    navigation: DshPageNavigation = DshPageNavigation.None,
    onNavigateBack: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    maxContentWidth: Dp = 720.dp,
    topBar: @Composable (() -> Unit)? = null,
    bottomBar: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Dsh.bgBase),
        contentAlignment = Alignment.TopCenter,
    ) {
        // 系统栏 inset 由本骨架统一消费（edge-to-edge + 透明系统栏）
        // F06：导航玻璃与内容区共用同一采样源——内容层录制、顶栏取样模糊，
        // 设置 / 设备不再退化为无 backdrop 的纯色半透明假玻璃。
        val backdrop = rememberLayerBackdrop()
        Column(
            modifier = Modifier
                .widthIn(max = maxContentWidth)
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            if (topBar != null) {
                topBar()
            } else if (title.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = PageNavHeight)
                        .dshTranslucent(showDivider = false, backdrop = backdrop)
                        .padding(horizontal = DshSpace.s4),
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
                    Text(
                        title,
                        color = Dsh.labelPrimary,
                        style = DshType.headlineMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = if (navigation == DshPageNavigation.None) 12.dp else 0.dp)
                            .semantics { heading() },
                    )
                    actions()
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // 采样源挂在内容层，与顶栏同级——不能挂到含玻璃的祖先（递归采样）
                    .layerBackdrop(backdrop),
            ) {
                content()
            }
            if (bottomBar != null) {
                bottomBar()
            }
        }
    }
}
