package dev.deeplinks.native.util

/**
 * DSH 斜杠命令/工具搜索（WI-005 / WI-006 抽出）。
 *
 * 行为对齐 DSH Web UI 的命令面板：
 * - 输入 `/` + 至少 2 字符时按命令 token 前缀/子串匹配（大小写不敏感）；
 * - < 2 字符时不过滤，原样返回全列表；
 * - 空查询（不含 `/`）视为不在命令面板，返回空列表（避免 UI 误显示）。
 *
 * 与 [dev.deeplinks.native.ui.DshFilterChip] 一起使用 —— 单纯纯函数，零 Compose 依赖，可 JVM 单测。
 */

/** 单个斜杠命令条目：`token` 是 `/name` 这种可输入片段，`description` 是右栏说明。 */
data class SlashCommand(val token: String, val description: String)

/** 一组同类的命令（例如 "Built-in"、"Permissions"、"Workspaces"）。 */
data class SlashCommandGroup(val title: String, val items: List<SlashCommand>)

/**
 * 把带搜索前缀的查询字符串过滤为命中的分组。
 *
 * @param groups 完整命令分组（保持输入顺序）
 * @param query 用户已输入的文本（含前导 `/`），允许为空或非命令输入
 * @return 过滤后的分组 —— 完全无命中时返回空列表
 */
fun filterSlashCommands(
    groups: List<SlashCommandGroup>,
    query: String,
): List<SlashCommandGroup> {
    if (!query.startsWith("/")) return emptyList()
    val prefix = query.removePrefix("/")
    if (prefix.length < 2) return groups // 至少 2 字符才过滤（避免输入 `/` 就把列表缩小）
    return groups.mapNotNull { g ->
        val filtered = g.items.filter { it.token.contains(prefix, ignoreCase = true) }
        if (filtered.isEmpty()) null else SlashCommandGroup(g.title, filtered)
    }
}