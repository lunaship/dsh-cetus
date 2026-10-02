package dev.deeplinks.native

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.DiagStatus
import dev.deeplinks.core.DiagStep
import dev.deeplinks.core.DiagnosticsReport
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshStrings
import dev.deeplinks.core.DshType
import dev.deeplinks.core.Host
import dev.deeplinks.core.HostCheckView
import dev.deeplinks.core.HostListTone
import dev.deeplinks.core.diagnosticsRunnerFor
import dev.deeplinks.native.ui.DshListActionRow
import dev.deeplinks.native.ui.DshListNote
import dev.deeplinks.native.ui.DshListRow
import dev.deeplinks.native.ui.DshListSection
import dev.deeplinks.native.ui.DshListTrailing
import dev.deeplinks.native.ui.DshPageNavigation
import dev.deeplinks.native.ui.DshPageScaffold
import dev.deeplinks.native.ui.DshSectionContainer
import dev.deeplinks.native.ui.LocalDshPageTopInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 → 某台电脑 → 连接诊断。打开时跑一遍 [diagnosticsRunnerFor]，不删除凭据。
 * [lanUrls] 默认是主地址，再加上已保存的 Tailscale 备用地址。
 */
@Composable
internal fun ConnectionDiagnosticsPage(
    host: Host,
    onBack: () -> Unit,
    lanUrls: List<String> = host.directLanUrls(),
) {
    var report by remember(host.slotKey) { mutableStateOf<DiagnosticsReport?>(null) }
    var running by remember(host.slotKey) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun start() {
        running = true
        scope.launch {
            val next = withContext(Dispatchers.IO) {
                diagnosticsRunnerFor(host, lanUrls).run()
            }
            report = next
            running = false
        }
    }

    LaunchedEffect(host.slotKey) { start() }

    ConnectionDiagnosticsScreen(
        report = report,
        running = running,
        onBack = onBack,
        onRun = ::start,
        onCopy = {
            val text = report?.copyText().orEmpty()
            if (text.isNotEmpty()) copyDiagnosticsText(context, text)
        },
    )
}

@Composable
internal fun ConnectionDiagnosticsScreen(
    report: DiagnosticsReport?,
    running: Boolean,
    onBack: () -> Unit,
    onRun: () -> Unit,
    onCopy: () -> Unit,
) {
    val s = DshS
    DshPageScaffold(
        title = s.translation("diagTitle"),
        navigation = DshPageNavigation.Back,
        onNavigateBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = LocalDshPageTopInset.current)
                .padding(horizontal = DshSpace.s16)
                .padding(bottom = DshSpace.s32),
        ) {
            if (running && report == null) {
                DshListSection(container = DshSectionContainer.Card) {
                    DshListNote(s.translation("diagRunning"))
                }
            }
            if (report != null) {
                DshListSection(
                    container = DshSectionContainer.Card,
                    header = s.translation("diagSectionLocal"),
                ) {
                    report.steps.forEach { step ->
                        DiagnosticStepRow(step, s)
                    }
                }
                val oldPlugin = report.steps.any { it.code == "AUTH_OLD_PLUGIN" }
                DshListSection(
                    container = DshSectionContainer.Card,
                    header = s.translation("diagSectionHost"),
                ) {
                    if (report.hostChecks.isEmpty()) {
                        DshListNote(
                            s.translation(if (oldPlugin) "diagOldPlugin" else "diagHostEmpty"),
                        )
                    } else {
                        report.hostChecks.forEach { check ->
                            HostCheckRow(check, s)
                        }
                    }
                }
            }
            DshListSection(container = DshSectionContainer.Card) {
                DshListActionRow(
                    label = s.translation("diagCopy"),
                    icon = CopyOutline16,
                    enabled = report != null && !running,
                    onClick = onCopy,
                )
                DshListActionRow(
                    label = s.translation("diagRerun"),
                    icon = RefreshOutline16,
                    enabled = !running,
                    onClick = onRun,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticStepRow(step: DiagStep, s: DshStrings) {
    val subtitle = when {
        step.suggestionKey != null -> s.translation(step.suggestionKey)
        step.detailKey != null -> s.translation(step.detailKey)
        step.elapsedMs != null -> "${step.elapsedMs} ms"
        else -> null
    }
    DshListRow(
        title = s.translation(stepTitleKey(step.id)),
        subtitle = subtitle,
        trailing = DshListTrailing.None,
        leading = { HostHealthDot(toneFor(step.status)) },
        trailingContent = {
            Text(
                s.translation(statusKey(step.status)),
                color = Dsh.labelSecondary,
                style = DshType.supporting,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun HostCheckRow(check: HostCheckView, s: DshStrings) {
    val status = when (check.status) {
        "ok" -> DiagStatus.OK
        "warn" -> DiagStatus.WARN
        "fail" -> DiagStatus.FAIL
        else -> DiagStatus.SKIP
    }
    DshListRow(
        title = check.id,
        subtitle = check.code,
        trailing = DshListTrailing.None,
        leading = { HostHealthDot(toneFor(status)) },
        trailingContent = {
            Text(
                s.translation(statusKey(status)),
                color = Dsh.labelSecondary,
                style = DshType.supporting,
                maxLines = 1,
            )
        },
    )
}

/** 设置页主机行的四色状态点。与首页的二态 [dev.deeplinks.native.ui.HostStatusDot] 分开，避免改动已有截图色。 */
@Composable
internal fun HostHealthDot(tone: HostListTone, size: Dp = 7.dp) {
    val color = when (tone) {
        HostListTone.GREEN -> Dsh.successContent
        HostListTone.YELLOW -> Dsh.warn
        HostListTone.RED -> Dsh.error
        HostListTone.GRAY -> Dsh.labelTertiary
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

private fun toneFor(status: DiagStatus): HostListTone = when (status) {
    DiagStatus.OK -> HostListTone.GREEN
    DiagStatus.WARN -> HostListTone.YELLOW
    DiagStatus.FAIL -> HostListTone.RED
    DiagStatus.SKIP -> HostListTone.GRAY
}

private fun stepTitleKey(id: String): String = when (id) {
    "network" -> "diagNetwork"
    "lan" -> "diagLan"
    "remote" -> "diagRemote"
    "cert" -> "diagCert"
    "auth" -> "diagAuth"
    "clock" -> "diagClock"
    else -> "diagTitle"
}

private fun statusKey(status: DiagStatus): String = when (status) {
    DiagStatus.OK -> "diagStatusOk"
    DiagStatus.WARN -> "diagStatusWarn"
    DiagStatus.FAIL -> "diagStatusFail"
    DiagStatus.SKIP -> "diagStatusSkip"
}

private fun copyDiagnosticsText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("dsh-diagnostics", text))
}
