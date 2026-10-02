package dev.deeplinks.native

/**
 * 会话用量的纯计算。界面只负责把结果画出来。
 *
 * 缓存命中率 = cacheRead / (cacheRead + uncachedInput)，分母为 0 时没有比率。
 * 平均首字延迟 = ttftMs / ttftSteps。输出速度 = decodeTokens / decodeMs，换成每秒。
 * 求和用 double，避免两个极大 Long 相加溢出。
 */
internal data class UsageSlice(val key: String, val tokens: Long)

internal data class UsageFigures(
    val uncachedInputTokens: Long,
    val cacheReadTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
    val cacheHitRate: Double?,
    val turns: Long,
    val steps: Long,
    val llmMs: Long,
    val toolMs: Long,
    val avgTtftMs: Double?,
    val outputTokensPerSec: Double?,
    val contextUsedTokens: Long,
    val contextWindowTokens: Long,
    val breakdown: List<UsageSlice>,
)

internal fun usageFigures(stats: MobileSessionStats?): UsageFigures? {
    if (stats == null) return null
    val uncached = stats.uncachedInputTokens.coerceAtLeast(0)
    val cache = stats.cacheReadTokens.coerceAtLeast(0)
    val output = stats.outputTokens.coerceAtLeast(0)
    val slices = listOf(
        UsageSlice("system", stats.systemTokens.coerceAtLeast(0)),
        UsageSlice("tools", stats.toolsTokens.coerceAtLeast(0)),
        UsageSlice("messages", stats.messageTokens.coerceAtLeast(0)),
    ).filter { it.tokens > 0 }
    return UsageFigures(
        uncachedInputTokens = uncached,
        cacheReadTokens = cache,
        outputTokens = output,
        totalTokens = saturatingTokenSum(uncached, cache, output),
        cacheHitRate = cacheHitRate(cache, uncached),
        turns = stats.turns.coerceAtLeast(0),
        steps = stats.steps.coerceAtLeast(0),
        llmMs = stats.llmMs.coerceAtLeast(0),
        toolMs = stats.toolMs.coerceAtLeast(0),
        avgTtftMs = averageTtftMs(stats.ttftMs, stats.ttftSteps),
        outputTokensPerSec = outputTokensPerSec(stats.decodeTokens, stats.decodeMs),
        contextUsedTokens = stats.contextPressureTokens.coerceAtLeast(0),
        contextWindowTokens = stats.contextWindow.coerceAtLeast(0),
        breakdown = slices,
    )
}

/** 分母为 0 时返回 null。两个 Long.MAX_VALUE 也能算出 0 到 1 之间的比率。 */
internal fun cacheHitRate(cacheRead: Long, uncachedInput: Long): Double? {
    val read = cacheRead.coerceAtLeast(0).toDouble()
    val miss = uncachedInput.coerceAtLeast(0).toDouble()
    val denom = read + miss
    if (denom == 0.0 || !denom.isFinite()) return null
    return (read / denom).coerceIn(0.0, 1.0)
}

internal fun averageTtftMs(ttftMs: Long, ttftSteps: Long): Double? {
    if (ttftSteps <= 0 || ttftMs < 0) return null
    val value = ttftMs.toDouble() / ttftSteps.toDouble()
    return value.takeIf { it.isFinite() }
}

internal fun outputTokensPerSec(decodeTokens: Long, decodeMs: Long): Double? {
    if (decodeMs <= 0 || decodeTokens < 0) return null
    val value = decodeTokens.toDouble() / decodeMs.toDouble() * 1000.0
    return value.takeIf { it.isFinite() }
}

internal fun saturatingTokenSum(vararg parts: Long): Long {
    var acc = 0.0
    for (part in parts) {
        if (part > 0) acc += part.toDouble()
    }
    if (!acc.isFinite() || acc <= 0.0) return 0
    if (acc >= Long.MAX_VALUE.toDouble()) return Long.MAX_VALUE
    return acc.toLong()
}
