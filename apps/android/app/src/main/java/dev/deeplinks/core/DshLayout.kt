package dev.deeplinks.core

/**
 * 自适应布局推导（对照 t3code lib/layout.ts）：
 * **用可用空间而不是设备/方向标签来决定外壳**，且全部是纯函数，可表驱动单测。
 *
 * 阈值对齐 Android 窗口尺寸类别，并额外加高度下限（与 t3code 的
 * SPLIT_LAYOUT_MIN_HEIGHT 同思路）：横屏手机不应该因为"够宽"就变成双栏。
 */
enum class DshShell { Compact, Medium, Expanded }

data class DshLayout(
    val shell: DshShell,
    /** 只有 Expanded 把列表常驻；Compact/Medium 用会话列表 → 聊天的返回栈。 */
    val persistentSidebar: Boolean,
    /** 保留字段。Medium 已并入手机栈，不再使用 Navigation Rail。 */
    val railNavigation: Boolean,
    /** 常驻侧栏宽度（dp）。 */
    val listPaneWidthDp: Int,
    /** 内容列最大宽度（dp），手机无上限时由调用方 fillMaxWidth。 */
    val contentMaxWidthDp: Int,
)

const val DSH_MEDIUM_MIN_WIDTH_DP = 600
const val DSH_EXPANDED_MIN_WIDTH_DP = 840
const val DSH_EXPANDED_MIN_HEIGHT_DP = 600
const val DSH_LIST_PANE_MIN_DP = 240
const val DSH_LIST_PANE_MAX_DP = 390 // 方案 9：宽屏左侧常驻收件箱宽 390（原 380）
const val DSH_CONTENT_MAX_DP = 760

/**
 * 三档外壳：
 * - Compact / Medium：会话列表是首页，聊天是压栈的下一页。Medium 不再加 Rail，
 *   避免「窄栏 + 再弹一层列表」的控制台感。
 * - Expanded（>=840dp 且 >=600dp 高）：常驻侧栏的 master-detail。
 */
fun deriveDshLayout(containerWidthDp: Int, containerHeightDp: Int): DshLayout {
    val width = containerWidthDp.coerceAtLeast(0)
    val height = containerHeightDp.coerceAtLeast(0)
    val shell = when {
        width < DSH_MEDIUM_MIN_WIDTH_DP -> DshShell.Compact
        width < DSH_EXPANDED_MIN_WIDTH_DP || height < DSH_EXPANDED_MIN_HEIGHT_DP -> DshShell.Medium
        else -> DshShell.Expanded
    }
    return DshLayout(
        shell = shell,
        persistentSidebar = shell == DshShell.Expanded,
        railNavigation = false,
        listPaneWidthDp = deriveListPaneWidth(width),
        contentMaxWidthDp = DSH_CONTENT_MAX_DP,
    )
}

/** 32% 宽、clamp 在 [240, 380] dp（与 t3code clamp(round(width*0.32), 280, 380) 同形）。 */
fun deriveListPaneWidth(containerWidthDp: Int): Int {
    val width = containerWidthDp.coerceAtLeast(0)
    return (width * 0.32f).toInt().coerceIn(DSH_LIST_PANE_MIN_DP, DSH_LIST_PANE_MAX_DP)
}

/** 内容列封顶，避免平板把手机布局无限拉宽。 */
fun constrainDshContentWidth(availableWidthDp: Int): Int {
    val width = availableWidthDp.coerceAtLeast(0)
    return minOf(width, DSH_CONTENT_MAX_DP)
}
