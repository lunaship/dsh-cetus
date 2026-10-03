package dev.deeplinks.native.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.native.ui.v4.DlTopBar
import dev.deeplinks.native.ui.v4.DlTopBarAction
import dev.deeplinks.native.ui.v4.DlTopBarNav

/**
 * 设置 / 设备 / 诊断这类二级页的壳层（v4）：实底 [DlTopBar] + 内容区，无玻璃、无渐隐。
 * 顶栏不再悬浮，内容从顶栏下方开始，[LocalDshPageTopInset] 恒为 0（保留给旧调用）。
 */

/** 旧悬浮顶栏的避让高度；v4 顶栏实底占位，恒为 0。 */
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
    actions: List<DlTopBarAction> = emptyList(),
    maxContentWidth: Dp = 720.dp,
    topBar: @Composable (() -> Unit)? = null,
    bottomBar: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Dsh.bgBase)
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (topBar != null) {
            topBar()
        } else if (title.isNotBlank()) {
            DlTopBar(
                title = title,
                nav = if (navigation == DshPageNavigation.Back) DlTopBarNav.Back else DlTopBarNav.None,
                onNav = onNavigateBack,
                actions = actions,
            )
        }
        Column(
            modifier = Modifier
                .widthIn(max = maxContentWidth)
                .fillMaxWidth()
                .weight(1f),
            content = content,
        )
        if (bottomBar != null) {
            Box(Modifier.fillMaxWidth().navigationBarsPadding()) { bottomBar() }
        }
    }
}
