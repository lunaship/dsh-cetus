package dev.deeplinks.native

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.ModelTier
import dev.deeplinks.core.TierGap
import dev.deeplinks.core.TierModel
import dev.deeplinks.core.TierPick

internal fun tierModelsOf(catalog: MobileModelCatalog?): List<TierModel> =
    catalog?.groups.orEmpty().flatMap { group ->
        group.models.map { model ->
            TierModel(
                provider = group.provider,
                id = model.id,
                name = model.name,
                contextWindow = model.contextWindow,
                efforts = model.reasoningEfforts,
            )
        }
    }

@Composable
internal fun tierLabel(tier: ModelTier): String = DshS.translation(
    when (tier) {
        ModelTier.Save -> "tierSave"
        ModelTier.Balanced -> "tierBalanced"
        ModelTier.Power -> "tierPower"
    },
)

@Composable
internal fun tierGapText(gap: TierGap?): String = DshS.translation(
    when (gap) {
        TierGap.CustomMissing -> "tierCustomMissing"
        TierGap.MissingDefault -> "tierNoDefault"
        else -> "tierNoModels"
    },
)

@Composable
internal fun ModelTierBar(
    picks: List<TierPick>,
    currentProvider: String?,
    currentModel: String?,
    currentEffort: String?,
    onPick: (provider: String, modelId: String, effort: String?) -> Unit,
) {
    if (picks.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(DshSpace.s8)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(DshRowHeight.default)
                .clip(RoundedCornerShape(DshRadius.control))
                .background(Dsh.bgSubtle),
        ) {
            picks.forEach { pick ->
                val selected = pick.enabled &&
                    pick.provider == currentProvider &&
                    pick.modelId == currentModel &&
                    pick.effort == currentEffort
                val label = tierLabel(pick.tier)
                val reason = if (pick.enabled) label else "$label ${tierGapText(pick.gap)}"
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(if (selected) Modifier.background(Dsh.bgCard) else Modifier)
                        .alpha(if (pick.enabled) 1f else 0.4f)
                        .semantics { contentDescription = reason }
                        .then(
                            if (pick.enabled && pick.provider != null && pick.modelId != null) {
                                Modifier.clickable(role = Role.Button) {
                                    onPick(pick.provider, pick.modelId, pick.effort)
                                }
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (selected) Dsh.labelPrimary else Dsh.labelSecondary,
                        style = if (selected) DshType.bodyStrong else DshType.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        picks.filter { !it.enabled }.forEach { pick ->
            Text(
                DshS.translation("tierReason").format(tierLabel(pick.tier), tierGapText(pick.gap)),
                color = Dsh.labelTertiary,
                style = DshType.captionRelaxed,
            )
        }
    }
}
