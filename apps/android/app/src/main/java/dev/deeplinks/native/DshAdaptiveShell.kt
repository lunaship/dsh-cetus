package dev.deeplinks.native

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshLayout

/**
 * 自适应工作区外壳：
 *
 * - Compact / Medium：只渲染 [content]。会话列表和聊天由内容自己按返回栈切换。
 * - Expanded（>=840dp 且高 >=600dp）：侧栏常驻，选中会话原地切换。
 *
 * 容器色走 [Dsh.bgDrawer]。分层感由末缘发丝线给出。
 * 顶部 inset 由 DrawerSheet 自带的 systemBars 负责，侧栏内容不得再叠一层。
 */
@Composable
internal fun DshAdaptiveShell(
    layout: DshLayout,
    sidebarCollapsed: Boolean = false,
    sidebar: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    if (layout.persistentSidebar) {
        PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(
                    drawerContainerColor = Dsh.bgDrawer,
                    drawerContentColor = Dsh.labelPrimary,
                    modifier = Modifier.width(if (sidebarCollapsed) 56.dp else layout.listPaneWidthDp.dp),
                ) { SidebarEdgeHairline { sidebar() } }
            },
        ) { content() }
        return
    }
    content()
}

/**
 * 容器与内容同系配色后，抽屉右缘的 1dp 发丝线是唯一硬边界。
 * 画在内容层之上（drawWithContent）才能盖过 sheet 自身的容器底；
 * 用 [Dsh.borderSubtle]（亮 10% 黑 / 暗 12% 白），随主题自动适配。
 */
@Composable
private fun SidebarEdgeHairline(content: @Composable () -> Unit) {
    val hairline = Dsh.borderSubtle
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                val stroke = 1.dp.toPx()
                val x = size.width - stroke / 2
                drawLine(
                    color = hairline,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = stroke,
                )
            },
    ) { content() }
}
