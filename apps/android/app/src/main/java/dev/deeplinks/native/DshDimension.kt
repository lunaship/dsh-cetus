package dev.deeplinks.native

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 内容高度常量（非间距刻度，不走 DshSpace）。
 *
 * 顶栏 / 输入区等固定高度在视觉规则中由组件自身决定，
 * 这里集中声明，避免 WorkspaceActivity.kt 等文件出现零散裸 dp。
 */
object DshDimension {
    /** WorkspaceTopBar 固有高度（56dp）。 */
    val TopBarHeight: Dp = 56.dp

    /** 底部输入区（ComposerBar + 上下文条 + shadow gap）高度。 */
    val InputAreaHeight: Dp = 120.dp
}
