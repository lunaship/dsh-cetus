package dev.deeplinks.native

import androidx.compose.ui.unit.dp

/**
 * 图标尺寸（docs/visual-rules.md 第五节）。
 *
 * 取值取自实际用到的图标尺寸：12/16/20/24 覆盖全部图标按钮、列表图标、状态图标；
 * 14/18 等中间档不常用，落到相邻一档。
 */
object DshIconSize {
    /** 微型图标（辅助说明、徽标、紧凑行）。 */
    val xs = 12.dp

    /** 标准图标（列表、菜单、按钮）。 */
    val sm = 16.dp

    /** 中等图标（概览卡片、状态标识）。 */
    val md = 20.dp

    /** 大图标（首页顶栏、空态、 Featured 区块）。 */
    val lg = 24.dp
}
