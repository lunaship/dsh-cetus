package dev.deeplinks.core

import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * 全局统一触摸反馈：低透明度**中性** ripple（2026-09-30）。
 *
 * 原生手感的第一信号——指尖挡住按钮时，ripple 是唯一告诉用户「按到了」的反馈。
 * 颜色改为 `labelPrimary`（浅色近黑、深色近白），不再用品牌蓝：品牌蓝只给批准 / 发送，
 * 且品牌蓝波纹叠在手动按压底色上会显得脏（P1）。
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
        LocalDshColors.current.labelPrimary.copy(alpha = 0.08f)
    } else {
        color
    },
    bounded = bounded,
    radius = radius,
)
