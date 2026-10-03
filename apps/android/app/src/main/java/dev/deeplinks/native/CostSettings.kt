package dev.deeplinks.native

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
import dev.deeplinks.core.parseAlertAmount
import dev.deeplinks.core.readBalanceAlert
import dev.deeplinks.core.writeBalanceAlert
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
