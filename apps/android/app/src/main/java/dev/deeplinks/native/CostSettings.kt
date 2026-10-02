package dev.deeplinks.native

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.deeplinks.core.BalanceAlertPref
import dev.deeplinks.core.DshS
import dev.deeplinks.core.Host
import dev.deeplinks.core.ModelTier
import dev.deeplinks.core.TierCustom
import dev.deeplinks.core.TierModel
import dev.deeplinks.core.parseAlertAmount
import dev.deeplinks.core.readBalanceAlert
import dev.deeplinks.core.readTierCustom
import dev.deeplinks.core.writeBalanceAlert
import dev.deeplinks.core.writeTierCustom
import dev.deeplinks.native.ui.DshListNote
import dev.deeplinks.native.ui.DshListRow
import dev.deeplinks.native.ui.DshListSection
import dev.deeplinks.native.ui.DshSectionContainer
import dev.deeplinks.native.ui.DshSheet
import dev.deeplinks.native.ui.DshSheetPrimaryButton
import dev.deeplinks.native.ui.DshSwitchRow
import dev.deeplinks.native.ui.DshTextField

@Composable
internal fun BalanceAlertSettings(host: Host?) {
    if (host == null) return
    val context = LocalContext.current
    var pref by remember(host.slotKey) { mutableStateOf(readBalanceAlert(context, host)) }
    var open by remember { mutableStateOf(false) }
    DshListSection(container = DshSectionContainer.Card, footer = DshS.translation("balanceAlertHint")) {
        DshListRow(
            title = DshS.translation("balanceAlert"),
            value = if (pref.enabled && pref.threshold != null) {
                formatMoney(pref.threshold!!.toPlainString())
            } else {
                DshS.translation("balanceAlertOff")
            },
            onClick = { open = true },
        )
    }
    if (open) {
        BalanceAlertSheet(pref, onDismiss = { open = false }) { next ->
            writeBalanceAlert(context, host, next)
            pref = readBalanceAlert(context, host)
            open = false
        }
    }
}

@Composable
private fun BalanceAlertSheet(
    current: BalanceAlertPref,
    onDismiss: () -> Unit,
    onSave: (BalanceAlertPref) -> Unit,
) {
    var enabled by remember { mutableStateOf(current.enabled) }
    var amount by remember { mutableStateOf(current.threshold?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    val parsed = parseAlertAmount(amount)
    DshSheet(onDismiss = onDismiss, title = DshS.translation("balanceAlert")) {
        DshSwitchRow(
            title = DshS.translation("balanceAlert"),
            subtitle = DshS.translation("balanceAlertHint"),
            checked = enabled,
            onCheckedChange = { enabled = it },
        )
        if (enabled) {
            Spacer(Modifier.height(DshSpace.s8))
            DshTextField(
                value = amount,
                onValueChange = { amount = it },
                label = DshS.translation("balanceAlertAmount"),
                isError = amount.isNotBlank() && parsed == null,
            )
        }
        Spacer(Modifier.height(DshSpace.s12))
        DshSheetPrimaryButton(
            label = DshS.save,
            enabled = !enabled || parsed != null,
            onClick = {
                onSave(if (enabled && parsed != null) BalanceAlertPref(true, parsed) else BalanceAlertPref())
            },
        )
    }
}

@Composable
internal fun ModelTierSettings(host: Host?, groups: List<MobileModelGroup>) {
    if (host == null) return
    val context = LocalContext.current
    var custom by remember(host.slotKey) { mutableStateOf(readTierCustom(context, host)) }
    var editing by remember { mutableStateOf<ModelTier?>(null) }
    val models = remember(groups) {
        groups.flatMap { group ->
            group.models.map { model ->
                TierModel(group.provider, model.id, model.name ?: model.id, efforts = model.reasoningEfforts)
            }
        }
    }
    DshListSection(container = DshSectionContainer.Card, header = DshS.translation("tierSection")) {
        ModelTier.entries.forEach { tier ->
            val chosen = custom[tier]
            DshListRow(
                title = tierLabel(tier),
                value = chosen?.let { nameOf(models, it) } ?: DshS.translation("tierAutomatic"),
                onClick = { editing = tier },
            )
        }
    }
    val tier = editing
    if (tier != null) {
        TierCustomSheet(
            tier = tier,
            models = models,
            current = custom[tier],
            onDismiss = { editing = null },
            onSave = { next ->
                val updated = custom.toMutableMap()
                if (next == null) updated.remove(tier) else updated[tier] = next
                writeTierCustom(context, host, updated)
                custom = readTierCustom(context, host)
                editing = null
            },
        )
    }
}

@Composable
private fun TierCustomSheet(
    tier: ModelTier,
    models: List<TierModel>,
    current: TierCustom?,
    onDismiss: () -> Unit,
    onSave: (TierCustom?) -> Unit,
) {
    var provider by remember { mutableStateOf(current?.provider) }
    var modelId by remember { mutableStateOf(current?.modelId) }
    var effort by remember { mutableStateOf(current?.effort) }
    val selected = models.firstOrNull { it.provider == provider && it.id == modelId }
    DshSheet(onDismiss = onDismiss, title = tierLabel(tier)) {
        Column {
            DshListSection {
                DshListRow(
                    title = DshS.translation("tierAutomatic"),
                    onClick = { onSave(null) },
                )
            }
            if (models.isEmpty()) {
                DshListNote(DshS.translation("tierNoModels"))
            } else {
                DshListSection {
                    models.forEach { model ->
                        DshListRow(
                            title = model.name ?: model.id,
                            value = model.provider,
                            onClick = {
                                provider = model.provider
                                modelId = model.id
                                effort = model.efforts.firstOrNull()
                            },
                        )
                    }
                }
            }
            val efforts = selected?.efforts.orEmpty()
            if (efforts.isNotEmpty()) {
                Spacer(Modifier.height(DshSpace.s8))
                DshListSection {
                    efforts.forEach { item ->
                        DshListRow(
                            title = formatEffortLabel(item),
                            onClick = { effort = item },
                        )
                    }
                }
            }
            Spacer(Modifier.height(DshSpace.s12))
            DshSheetPrimaryButton(
                label = DshS.save,
                enabled = selected != null,
                onClick = {
                    val model = selected ?: return@DshSheetPrimaryButton
                    onSave(TierCustom(model.provider, model.id, effort))
                },
            )
        }
    }
}

private fun nameOf(models: List<TierModel>, custom: TierCustom): String =
    models.firstOrNull { it.provider == custom.provider && it.id == custom.modelId }?.name ?: custom.modelId
