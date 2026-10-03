package dev.deeplinks.native

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.core.shareExportFormat
import dev.deeplinks.core.shareTurnCount
import dev.deeplinks.native.ui.v4.DlBottomSheet
import dev.deeplinks.native.ui.v4.DlInsetColor
import dev.deeplinks.native.ui.v4.DlListRow
import dev.deeplinks.native.util.workspaceDisplayName
import dev.deeplinks.native.util.selectShareTurns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 5.9 预览卡的内容：标题、「工作区 · N 轮」、最近一条回复的开头。 */
internal data class ConversationSharePreview(val title: String, val meta: String, val excerpt: String?)

internal fun conversationSharePreview(title: String, cwd: String?, messages: List<MobileMessage>): ConversationSharePreview {
    val turns = messages.count { it.role == "user" }
    val meta = listOfNotNull(
        cwd?.takeIf { it.isNotBlank() }?.let(::workspaceDisplayName),
        if (turns > 0) L.shareTurnCount.format(turns) else null,
    ).joinToString(" · ")
    val excerpt = messages.lastOrNull { it.role == "assistant" && it.text.isNotBlank() }
        ?.text?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(" ")?.take(SHARE_EXCERPT_CHARS)
    return ConversationSharePreview(title, meta, excerpt)
}

private const val SHARE_EXCERPT_CHARS = 140

/**
 * 5.9 分享对话：先给预览卡，再给「分享为图片」「导出为文本」两种方式。
 * 具体渲染与导出逻辑在本文件下方。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationShareSheet(
    preview: ConversationSharePreview?,
    onDismiss: () -> Unit,
    onShareImage: () -> Unit,
    onExportText: () -> Unit,
) {
    DlBottomSheet(onDismissRequest = onDismiss, title = L.shareConversation) {
        ConversationShareContent(preview, onShareImage, onExportText)
    }
}

/** 弹层正文，截图直接画这一块。 */
@Composable
internal fun ConversationShareContent(
    preview: ConversationSharePreview?,
    onShareImage: () -> Unit,
    onExportText: () -> Unit,
) {
    if (preview != null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = DshSpace.s16, end = DshSpace.s16, bottom = DshSpace.s8)
                .clip(RoundedCornerShape(DshRadius.container))
                .background(DlInsetColor)
                .padding(DshSpace.s16),
            verticalArrangement = Arrangement.spacedBy(DshSpace.s4),
        ) {
            Text(preview.title, style = DshType.bodyStrong, color = Dsh.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (preview.meta.isNotEmpty()) Text(preview.meta, style = DshType.supporting, color = Dsh.labelSecondary)
            preview.excerpt?.let {
                Text(
                    it,
                    style = DshType.supporting,
                    color = Dsh.labelPrimary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = DshSpace.s4),
                )
            }
        }
    }
    DlListRow(title = L.shareConversationImage, leading = ImageOutline16, onClick = onShareImage)
    DlListRow(title = L.exportConversation, subtitle = DshS.shareExportFormat, leading = FileOutline16, onClick = onExportText)
}

/** 分享为图片：拉全量消息 → 选最近若干轮 → 渲染 PNG → 走系统分享。 */
internal fun shareConversationAsImage(
    scope: CoroutineScope,
    context: Context,
    client: MobileApiClient,
    sessionId: String,
    title: String,
    dark: Boolean,
    onError: (String) -> Unit,
) {
    scope.launch(Dispatchers.IO) {
        try {
            val turns = selectShareTurns(loadSessionMessagesForExport(client, sessionId))
            withContext(Dispatchers.Main) {
                if (turns.isEmpty()) {
                    onError(L.shareConversationEmpty)
                } else {
                    val bitmap: Bitmap = ShareCardRenderer.render(title, turns, dark, "DeepLinks")
                    try {
                        ShareCardRenderer.sharePng(context, bitmap, title, L.shareConversationImage)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onError(L.exportFailed.format(e.message ?: L.unknownError)) }
        }
    }
}

/** 导出为文本：拉全量消息 → 拼纯文本 → 走系统分享。 */
internal fun exportConversationText(
    scope: CoroutineScope,
    context: Context,
    client: MobileApiClient,
    sessionId: String,
    title: String,
    onError: (String) -> Unit,
) {
    scope.launch(Dispatchers.IO) {
        try {
            val text = exportSessionTranscript(client, sessionId, title)
            withContext(Dispatchers.Main) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(send, L.exportConversation))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onError(L.exportFailed.format(e.message ?: L.unknownError)) }
        }
    }
}
