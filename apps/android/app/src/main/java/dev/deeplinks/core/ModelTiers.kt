package dev.deeplinks.core

/**
 * 省钱 / 均衡 / 最强。不写死模型 id，只从当前电脑给出的模型列表里解析。
 *
 * 没有单价时：省钱取上下文窗口最小的，最强取最大的；都没有窗口时按目录顺序，
 * 省钱取第一条，最强取最后一条。有单价时只在带单价的模型里比输入价。
 * 均衡用 Host 默认模型。推理强度按该模型自己的列表：已知档位按低到高，
 * 否则保持目录顺序；最低、中位、最高分别对应三档。
 */
enum class ModelTier { Save, Balanced, Power }

enum class TierGap { EmptyCatalog, MissingDefault, CustomMissing }

data class TierModel(
    val provider: String,
    val id: String,
    val name: String? = null,
    val contextWindow: Long? = null,
    val efforts: List<String> = emptyList(),
    val inputPrice: Double? = null,
)

data class TierCustom(val provider: String, val modelId: String, val effort: String?)

data class TierPick(
    val tier: ModelTier,
    val provider: String? = null,
    val modelId: String? = null,
    val modelName: String? = null,
    val effort: String? = null,
    val gap: TierGap? = null,
) {
    val enabled: Boolean get() = gap == null && !modelId.isNullOrBlank() && !provider.isNullOrBlank()
}

private val knownEffortOrder = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

internal fun orderedEfforts(efforts: List<String>): List<String> {
    if (efforts.isEmpty()) return efforts
    val known = efforts.all { it.lowercase() in knownEffortOrder }
    return if (known) efforts.sortedBy { knownEffortOrder.indexOf(it.lowercase()) } else efforts
}

internal fun effortForSlot(efforts: List<String>, slot: ModelTier): String? {
    val ordered = orderedEfforts(efforts)
    if (ordered.isEmpty()) return null
    val index = when (slot) {
        ModelTier.Save -> 0
        ModelTier.Power -> ordered.lastIndex
        ModelTier.Balanced -> ordered.size / 2
    }
    return ordered[index]
}

internal fun resolveModelTiers(
    models: List<TierModel>,
    hostDefaultProvider: String?,
    hostDefaultModel: String?,
    custom: Map<ModelTier, TierCustom> = emptyMap(),
): List<TierPick> = ModelTier.entries.map { tier ->
    resolveOne(tier, models, hostDefaultProvider, hostDefaultModel, custom[tier])
}

private fun resolveOne(
    tier: ModelTier,
    models: List<TierModel>,
    hostDefaultProvider: String?,
    hostDefaultModel: String?,
    custom: TierCustom?,
): TierPick {
    if (custom != null) {
        val match = findModel(models, custom.provider, custom.modelId)
            ?: return TierPick(tier, gap = TierGap.CustomMissing)
        val effort = custom.effort?.takeIf { match.efforts.isEmpty() || it in match.efforts }
            ?: effortForSlot(match.efforts, tier)
        return pick(tier, match, effort)
    }
    if (models.isEmpty()) return TierPick(tier, gap = TierGap.EmptyCatalog)
    if (tier == ModelTier.Balanced) {
        if (hostDefaultModel.isNullOrBlank()) return TierPick(tier, gap = TierGap.MissingDefault)
        val match = findModel(models, hostDefaultProvider, hostDefaultModel)
            ?: models.firstOrNull { it.id == hostDefaultModel }
            ?: return TierPick(tier, gap = TierGap.MissingDefault)
        return pick(tier, match, effortForSlot(match.efforts, tier))
    }
    val match = if (tier == ModelTier.Save) cheapest(models) else strongest(models)
    return pick(tier, match, effortForSlot(match.efforts, tier))
}

private fun pick(tier: ModelTier, model: TierModel, effort: String?) = TierPick(
    tier = tier,
    provider = model.provider,
    modelId = model.id,
    modelName = model.name ?: model.id,
    effort = effort,
)

private fun findModel(models: List<TierModel>, provider: String?, id: String): TierModel? {
    val byBoth = models.firstOrNull { it.id == id && (provider.isNullOrBlank() || it.provider == provider) }
    return byBoth ?: models.firstOrNull { it.id == id && provider.isNullOrBlank() }
}

private fun cheapest(models: List<TierModel>): TierModel {
    val priced = models.filter { it.inputPrice != null }
    if (priced.isNotEmpty()) {
        return priced.minWith(compareBy<TierModel> { it.inputPrice!! }.thenBy { models.indexOf(it) })
    }
    val sized = models.filter { (it.contextWindow ?: 0L) > 0L }
    val pool = sized.ifEmpty { models }
    return pool.minWith(compareBy<TierModel> { it.contextWindow ?: Long.MAX_VALUE }.thenBy { models.indexOf(it) })
}

private fun strongest(models: List<TierModel>): TierModel {
    val priced = models.filter { it.inputPrice != null }
    if (priced.isNotEmpty()) {
        return priced.maxWith(compareBy<TierModel> { it.inputPrice!! }.thenBy { models.indexOf(it) })
    }
    val sized = models.filter { (it.contextWindow ?: 0L) > 0L }
    val pool = sized.ifEmpty { models }
    return pool.maxWith(compareBy<TierModel> { it.contextWindow ?: Long.MIN_VALUE }.thenBy { models.indexOf(it) })
}
