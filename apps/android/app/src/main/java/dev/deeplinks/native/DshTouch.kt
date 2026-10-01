package dev.deeplinks.native

import androidx.compose.ui.unit.dp

/**
 * 触控热区（docs/visual-rules.md 第五节）。
 *
 * Material 3 推荐 ≥48dp；本端最小可点击热区 [min]，列表/紧凑行可以略降到 [compact]
 * 但必须保证图标视觉不变、外框不变，只放大 clickable 热区。
 */
object DshTouch {
    /** 推荐热区（按钮、FAB、Chip、菜单项）。 */
    val min = 48.dp

    /** 紧凑列表里可以接受的最小热区（必须配合 `clickable` 的 `minInteractiveTouchTargetSize` 或 padding 补足）。 */
    val compact = 40.dp
}
