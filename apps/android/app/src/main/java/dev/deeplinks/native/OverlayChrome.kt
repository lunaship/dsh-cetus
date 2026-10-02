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
import dev.deeplinks.core.Dsh
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
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

/** 底部实底上沿的过渡渐变高度（方案 A：上下 chrome 实底，只在底部上沿留一段短渐变）。 */
internal val BOTTOM_CHROME_FADE = 16.dp

/**
 * 顶部悬浮区（方案 A，2026-10-02）：贴顶、实底（与页面画布同色）、吃状态栏、回填高度。
 *
 * - 实底从屏幕顶端（含状态栏）画到顶栏下沿；内容滚到下面时只在下沿画一条细分隔线（[showDivider]）。
 * - **回填高度必须包含状态栏**：`onSizeChanged` 要放在 `statusBarsPadding()` 之前，
 *   否则测到的是去掉状态栏后的内高，内容区少让出一整条状态栏（小米 15 上轨迹工具条被压在标题下）。
 *   `ChromeAndMotionContractTest` 守着这个顺序。
 */
@Composable
internal fun Modifier.overlayTopChrome(
    state: OverlayChromeState,
    base: Color,
    showDivider: Boolean,
    consumeStatusBar: Boolean = true,
): Modifier {
    val dividerColor = Dsh.borderSubtle
    return this
        .fillMaxWidth()
        .zIndex(1f)
        .onSizeChanged { state.topPx = it.height }
        .drawBehind {
            drawRect(base)
            if (showDivider) {
                drawLine(
                    color = dividerColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = (1.dp / 2).toPx(),
                )
            }
        }
        .then(if (consumeStatusBar) Modifier.statusBarsPadding() else Modifier)
}

/**
 * 底部悬浮区（方案 A）：实底托住建议行、座位行与输入行，回填高度。
 * 实底上沿向上画 [BOTTOM_CHROME_FADE] 的画布色渐变（透明 → 实色），让滚动内容柔和收尾，
 * 不再有正文压在建议胶囊下面的「字叠字」。
 * [base] = null 时不画底（首页：悬浮胶囊 + 边缘渐隐，L9 样式不变）。
 */
@Composable
internal fun Modifier.overlayBottomChrome(state: OverlayChromeState, base: Color? = Dsh.bgBase): Modifier = this
    .fillMaxWidth()
    .zIndex(1f)
    .onSizeChanged { state.bottomPx = it.height }
    .drawBehind {
        if (base == null) return@drawBehind
        val fade = BOTTOM_CHROME_FADE.toPx()
        drawRect(
            brush = Brush.verticalGradient(listOf(base.copy(alpha = 0f), base), startY = -fade, endY = 0f),
            topLeft = Offset(0f, -fade),
            size = Size(size.width, fade),
        )
        drawRect(base)
    }

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
