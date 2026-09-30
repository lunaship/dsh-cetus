package dev.deeplinks.native

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import dev.deeplinks.core.DshLayout
import dev.deeplinks.core.deriveDshLayout
import dev.deeplinks.native.util.isChatVisible

/**
 * WorkspaceScreen 的布局派生（第三轮 S6 抽出）。
 *
 * 用实际窗口容器宽度而不是设备屏幕宽度：分屏、自由窗口、折叠屏下 `screenWidthDp` 会失真。
 * 另外给出 [chatVisible]（常驻侧栏视为始终可见），首页阶段据此决定要不要为当前会话预加载。
 */
internal data class WorkspaceChrome(
    val layout: DshLayout,
    val containerWidthDp: Dp,
    val displayDest: String,
    val showSessionHome: Boolean,
    val chatVisible: Boolean,
)

@Composable
internal fun rememberWorkspaceChrome(
    phoneDest: String,
    currentSessionId: String?,
    composeNewSession: Boolean,
    chatDest: String,
    sessionsDest: String,
    onCollapseToChat: () -> Unit,
): WorkspaceChrome {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val containerWidthDp = with(density) { windowInfo.containerSize.width.toDp() }
    val containerHeightDp = with(density) { windowInfo.containerSize.height.toDp() }
    val layout = remember(containerWidthDp, containerHeightDp) {
        deriveDshLayout(containerWidthDp.value.toInt(), containerHeightDp.value.toInt())
    }
    var prevPersistent by remember { mutableStateOf(layout.persistentSidebar) }
    val collapsingToPhone = prevPersistent && !layout.persistentSidebar
    val displayDest = when {
        layout.persistentSidebar -> chatDest
        collapsingToPhone && (currentSessionId != null || composeNewSession) -> chatDest
        else -> phoneDest
    }
    SideEffect {
        if (collapsingToPhone && (currentSessionId != null || composeNewSession)) onCollapseToChat()
        prevPersistent = layout.persistentSidebar
    }
    return WorkspaceChrome(
        layout = layout,
        containerWidthDp = containerWidthDp,
        displayDest = displayDest,
        showSessionHome = !layout.persistentSidebar && displayDest == sessionsDest,
        chatVisible = isChatVisible(layout.persistentSidebar, displayDest, chatDest),
    )
}
