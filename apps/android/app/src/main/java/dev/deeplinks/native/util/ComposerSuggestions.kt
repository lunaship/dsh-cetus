package dev.deeplinks.native.util

import dev.deeplinks.native.MobileMessage

/**
 * 建议行（2026-10-02 Lody 简化 4.3）的纯逻辑：
 * 显示条件 = 对话视图 · 电脑在线 · 未在执行 · 最后一个分组是已结束的助手回复 ·
 * 输入框为空 · 无待处理审批 / 提问。用户开始输入即隐藏（「查看改动」除外，有改动就常显）。
 * 快捷胶囊只预填、不发送（L8）；不提供 Commit & Push。
 */
fun composerSuggestionVisible(
    viewMode: String,
    running: Boolean,
    lastGroupEndedAssistant: Boolean,
    inputBlank: Boolean,
    pendingBlocked: Boolean,
    online: Boolean,
): Boolean = viewMode == "chat" && online && !running && lastGroupEndedAssistant &&
    inputBlank && !pendingBlocked

/** 最后一个分组是「已结束的助手回复」：流式运行中（running 标记）不算结束。 */
fun lastGroupEndedAssistant(messages: List<MobileMessage>, running: Boolean): Boolean {
    if (running) return false
    val last = messages.lastOrNull() ?: return false
    return last.role == "assistant" && last.running != true
}

/**
 * L6：顶栏「⋯」菜单首项文字随当前视图切换——对话视图显示「查看轨迹」，
 * 轨迹视图显示「返回对话」。
 */
fun viewModeToggleLabel(viewMode: String, showTrace: String, showChat: String): String =
    if (viewMode == "trace") showChat else showTrace
