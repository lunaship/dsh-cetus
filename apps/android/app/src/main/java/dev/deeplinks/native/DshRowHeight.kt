package dev.deeplinks.native

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 行高（docs/visual-rules.md 第五节）。
 *
 * 列表行只控制内容区高度；触控热区另由 [DshTouch] 控制。
 */
object DshRowHeight {
    /** 紧凑行（侧栏筛选、上下文菜单）。 */
    val compact = 40.dp

    /** 默认行（会话列表、工作区列表）。 */
    val `default` = 48.dp

    /** 展开行（带操作按钮的列表项）。 */
    val expanded = 56.dp
}
