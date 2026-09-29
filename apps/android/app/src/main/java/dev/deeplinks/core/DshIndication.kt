package dev.deeplinks.core

import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * 全局统一触摸反馈：低透明度品牌色 ripple。
 *
 * 原生手感的第一信号——指尖挡住按钮时，ripple 是唯一告诉用户「按到了」的反馈。
 * 之前全 App 用 `indication = dshRipple()` 关掉了它（网页 `:active` 思维），
 * 浅色主题使用品牌蓝，避免主文字近黑色波纹在浅底上显得像黑影。
 *
 * 用法：`.clickable(interactionSource = interaction, indication = dshRipple(), onClick = ...)`
 */
@Composable
fun dshRipple(
    bounded: Boolean = true,
    radius: Dp = Dp.Unspecified,
    color: Color = Color.Unspecified,
) = ripple(
    color = if (color == Color.Unspecified) {
        LocalDshColors.current.brand500.copy(alpha = 0.08f)
    } else {
        color
    },
    bounded = bounded,
    radius = radius,
)
