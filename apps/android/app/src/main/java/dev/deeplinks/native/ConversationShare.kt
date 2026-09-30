package dev.deeplinks.native

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import dev.deeplinks.core.L
import dev.deeplinks.native.ui.DshListRow
import dev.deeplinks.native.ui.DshListSection
import dev.deeplinks.native.ui.DshSheet
import dev.deeplinks.native.util.selectShareTurns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 对话分享（C1）：顶栏「⋯」里的「分享」打开这个小面板，两行分别走原来的分享图 / 导出文本。
 * 具体渲染与导出逻辑从 WorkspaceActivity 抽到这里，菜单调用块因此变短。
 */
@Composable
internal fun ConversationShareSheet(
    onDismiss: () -> Unit,
    onShareImage: () -> Unit,
    onExportText: () -> Unit,
) {
    DshSheet(onDismiss = onDismiss, title = L.shareConversation) {
        DshListSection {
            DshListRow(
                title = L.shareConversationImage,
                icon = ImageOutline16,
                onClick = onShareImage,
            )
            DshListRow(
                title = L.exportConversation,
                icon = ShareOutline16,
                onClick = onExportText,
            )
        }
    }
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
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onError(L.exportFailed.format(e.message ?: L.unknownError)) }
        }
    }
}
