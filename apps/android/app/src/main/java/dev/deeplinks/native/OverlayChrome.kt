package dev.deeplinks.native

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 叠层布局（UI 精简整改第 5 步）：内容铺满，顶部 / 底部 chrome 悬浮在上，
 * chrome 实测高度回填给内容区做 contentPadding——不再用写死的占位高度。
 */
@Stable
internal class OverlayChromeState(val backdrop: LayerBackdrop? = null) {
    var topPx by mutableIntStateOf(0)
    var bottomPx by mutableIntStateOf(0)
}

/** 带毛玻璃采样层：内容区挂 [overlayBackdropSource]，顶 / 底 chrome 的 dshTranslucent 从中取样模糊。 */
@Composable
internal fun rememberOverlayChromeState(): OverlayChromeState {
    val backdrop = rememberLayerBackdrop()
    return remember(backdrop) { OverlayChromeState(backdrop) }
}

/** 挂在被 chrome 覆盖的内容区（chrome 的同级，不能是祖先，否则会递归采样）。 */
internal fun Modifier.overlayBackdropSource(state: OverlayChromeState): Modifier =
    state.backdrop?.let { this.layerBackdrop(it) } ?: this

@Composable
internal fun OverlayChromeState.topDp(): Dp = with(LocalDensity.current) { topPx.toDp() }

@Composable
internal fun OverlayChromeState.bottomDp(): Dp = with(LocalDensity.current) { bottomPx.toDp() }

/** 顶部悬浮区：贴顶、压在内容之上、半透明（分隔线画在底边）、吃状态栏、回填高度。 */
@Composable
internal fun Modifier.overlayTopChrome(
    state: OverlayChromeState,
    base: Color,
    showDivider: Boolean,
    consumeStatusBar: Boolean = true,
): Modifier = this
    .fillMaxWidth()
    .zIndex(1f)
    .dshTranslucent(base = base, showDivider = showDivider, dividerAtTop = false, backdrop = state.backdrop)
    .then(if (consumeStatusBar) Modifier.statusBarsPadding() else Modifier)
    .onSizeChanged { state.topPx = it.height }

/** 底部悬浮区：压在内容之上、回填高度（背景由内部输入区自己画）。 */
internal fun Modifier.overlayBottomChrome(state: OverlayChromeState): Modifier = this
    .fillMaxWidth()
    .zIndex(1f)
    .onSizeChanged { state.bottomPx = it.height }

/** 底部 chrome 变高（键盘弹起 / 命令候选展开）时：贴底用户按增高像素上推，最新消息不被盖住。 */
@Composable
internal fun FollowBottomChromeGrowth(listState: LazyListState, bottomPx: Int, follow: Boolean) {
    var last by remember { mutableIntStateOf(0) }
    LaunchedEffect(bottomPx) {
        val prev = last
        last = bottomPx
        if (prev > 0 && bottomPx > prev && follow) {
            try {
                listState.scrollBy((bottomPx - prev).toFloat())
            } catch (_: Exception) {
            }
        }
    }
}

/** 下拉刷新指示器：让开顶部悬浮区，否则会藏在顶栏后面。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoxScope.ChromeAwareRefreshIndicator(state: PullToRefreshState, refreshing: Boolean, top: Dp) {
    PullToRefreshDefaults.Indicator(
        state = state,
        isRefreshing = refreshing,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = top),
    )
}
