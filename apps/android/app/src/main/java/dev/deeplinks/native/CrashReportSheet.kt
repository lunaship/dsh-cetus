package dev.deeplinks.native

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.CrashRecorder
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.native.ui.DshListRow
import dev.deeplinks.native.ui.DshListSection
import dev.deeplinks.native.ui.DshPillButton
import dev.deeplinks.native.ui.DshPillTone
import dev.deeplinks.native.ui.DshSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * K0 崩溃记录入口：有记录时在设置页多一行「上次崩溃 · <时间>」；点开是等宽文本面板，可复制 / 分享 / 清除。
 * 没有记录时整块不渲染。读取在 IO 线程，避免主线程读文件。
 */
@Composable
internal fun CrashReportEntry() {
    val s = DshS
    val context = LocalContext.current
    val report by produceState<String?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { CrashRecorder.readLatest() }
    }
    var open by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }
    val text = report
    if (cleared || text.isNullOrBlank()) return

    DshListSection {
        DshListRow(
            title = s.crashLast,
            subtitle = crashSummaryLine(text),
            icon = InfoOutline16,
            subtitleMono = true,
            onClick = { open = true },
        )
    }
    if (open) {
        DshSheet(
            onDismiss = { open = false },
            title = s.crashLast,
            skipPartiallyExpanded = true,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .clip(RoundedCornerShape(DshRadius.container))
                    .background(Dsh.bgInput)
                    .verticalScroll(rememberScrollState())
                    .padding(DshSpace.s12),
            ) {
                Text(
                    text = text,
                    color = Dsh.labelSecondary,
                    style = DshType.captionRelaxed.copy(fontFamily = FontFamily.Monospace),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = DshSpace.s16),
                horizontalArrangement = Arrangement.spacedBy(DshSpace.s8),
            ) {
                DshPillButton(
                    label = s.copy,
                    tone = DshPillTone.Accent,
                    onClick = { copyToClipboard(context, text) },
                )
                DshPillButton(
                    label = s.crashShare,
                    tone = DshPillTone.Tonal,
                    onClick = { shareText(context, text) },
                )
                DshPillButton(
                    label = s.crashClear,
                    tone = DshPillTone.Tonal,
                    onClick = {
                        CrashRecorder.clear()
                        cleared = true
                        open = false
                    },
                )
            }
        }
    }
}

/** 第一行取时间：`时间: 2026-…` → 只留时间值，给行副标题用。 */
internal fun crashSummaryLine(text: String): String? {
    val line = text.lineSequence().firstOrNull { it.startsWith("时间:") } ?: return null
    return line.removePrefix("时间:").trim().takeIf { it.isNotBlank() }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("DeepLinks crash", text))
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
