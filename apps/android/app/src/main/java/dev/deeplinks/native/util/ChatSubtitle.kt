package dev.deeplinks.native.util

import dev.deeplinks.core.L
import dev.deeplinks.native.MobileSessionActivity

/**
 * 对话页顶栏副标题（2026-09-28 重设计 · 稿 03/10）。
 *
 * - 平时：「工作区 · 电脑名」，回答「这是哪个项目的哪个会话」；
 * - 执行中：「◌ 正在执行 · 第 N 步 · M 分钟」，回答「它跑到哪了」。
 *
 * 步号来自插件下发的 activity（阶段 2），没有就省略；时长为 0 时省略，
 * 避免刚发出去就写「0 分钟」。
 */
fun chatTopSubtitle(
    running: Boolean,
    workspaceLabel: String?,
    hostName: String,
    activity: MobileSessionActivity? = null,
    elapsedSec: Long = 0L,
): String? {
    if (running) {
        val parts = mutableListOf("◌ ${L.chatExecuting}")
        activity?.step?.takeIf { it > 0 }?.let { parts += L.homeStepLabel.format(it) }
        if (elapsedSec > 0) parts += L.minutesShort.format((elapsedSec / 60).coerceAtLeast(1))
        return parts.joinToString(" · ")
    }
    return listOfNotNull(
        workspaceLabel?.trim()?.takeIf { it.isNotEmpty() },
        hostName.trim().takeIf { it.isNotEmpty() },
    ).joinToString(" · ").ifBlank { null }
}
