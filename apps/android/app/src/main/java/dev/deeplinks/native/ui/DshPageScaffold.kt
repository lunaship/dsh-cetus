package dev.deeplinks.native.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.deeplinks.core.Dsh

/**
 * 一级页面统一骨架（docs/visual-rules.md 第一节；2026-10-02 L9 悬浮化改造）。
 *
 * 页面不得自行决定标题、边距和背景：所有一级页面都是
 * 「页面导航区（返回/设备上下文 · 标题 · 页面动作）+ 页面内容」，
 * 画布固定 [Dsh.bgBase]，Compact 水平边距 16dp，Medium/Expanded 内容
 * 最大宽度 [maxContentWidth]（聊天工作区与双栏布局不受此限制）。
 *
 * 2026-10-02 L9：全宽导航玻璃条下线。顶部 chrome（标准页标题 + 返回圆钮）
 * 悬浮在内容之上，内容铺满整屏、滚动时从 chrome 下面穿过；交界处用边缘渐隐
 * （[dshEdgeFade]）柔化，渐隐层不进采样源。内容滚动容器用
 * [LocalDshPageTopInset]（含状态栏的 chrome 实测高度）做顶部 contentPadding。
 */

/** 顶部悬浮区实测高度（含状态栏 inset）：页面滚动容器把它当顶部 contentPadding 用。 */
val LocalDshPageTopInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

/** 页面导航区左侧内容：无 / 返回按钮。 */
enum class DshPageNavigation {
    None,
    Back,
}

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
        // F06：chrome 与内容区共用同一采样源——内容层录制、返回圆钮取样模糊；
        // 渐隐层在采样源之外，控件不会采到渐隐色
        val backdrop = rememberLayerBackdrop()
        var topPx by remember { mutableIntStateOf(0) }
        val topInset = with(LocalDensity.current) { topPx.toDp() }

        // 内容层：铺满整屏（画布延伸到状态栏下），滚动时穿过顶部 chrome
        Column(
            modifier = Modifier
                .widthIn(max = maxContentWidth)
                .fillMaxSize()
                .layerBackdrop(backdrop),
        ) {
            CompositionLocalProvider(LocalDshPageTopInset provides topInset) {
                content()
            }
        }
        if (bottomBar != null) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            ) {
                bottomBar()
            }
        }
        // 顶部渐隐：画布色 @0.94 → 0；内容未滚入时（同色叠同色）不可见
        if (topInset > 0.dp) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(topInset)
                    .zIndex(0.5f)
                    .dshEdgeFade(
                        edge = DshEdgeFadeEdge.Top,
                        visible = true,
                        canvasColor = Dsh.bgBase,
                        height = topInset,
                    ),
            )
        }
        // 顶部悬浮 chrome：贴顶、压在内容之上；实测高度回填给内容做顶部避让
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .zIndex(1f)
                .onSizeChanged { topPx = it.height }
                .statusBarsPadding(),
        ) {
            if (topBar != null) {
                topBar()
            } else if (title.isNotBlank()) {
                // 标准页 64dp 档（R2/R3）：标题、返回与页面动作统一由 DshPageChrome 布局
                DshPageChrome(
                    title = title,
                    density = DshPageChromeDensity.Standard,
                    navigation = navigation,
                    onNavigateBack = onNavigateBack,
                    backdrop = backdrop,
                    actions = actions,
                )
            }
        }
    }
}
