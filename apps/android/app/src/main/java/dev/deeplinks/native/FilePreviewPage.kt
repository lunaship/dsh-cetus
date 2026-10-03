package dev.deeplinks.native

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.fileCopyPath
import dev.deeplinks.core.filePathCopied
import dev.deeplinks.core.fileQuote
import dev.deeplinks.core.fileShare
import dev.deeplinks.native.ui.v4.DlAction
import dev.deeplinks.native.ui.v4.DlButton
import dev.deeplinks.native.ui.v4.DlButtonStyle
import dev.deeplinks.native.ui.v4.DlTopBar
import dev.deeplinks.native.ui.v4.DlTopBarNav
import dev.deeplinks.native.util.formatFileSize
import dev.deeplinks.native.util.producedFileName

/**
 * 6.4 文件预览：全屏，代码和 Markdown 都按等宽原文显示（可选中），
 * 底部三个动作：复制路径 / 分享 / 引用到对话（[onQuote] 为 null 时不出第三个）。
 */
@Composable
internal fun FilePreviewDialog(
    path: String,
    body: String,
    onDismiss: () -> Unit,
    onQuote: ((String) -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        FilePreviewPage(path, body, onDismiss, onQuote)
    }
}

/** 页面本体，截图直接画这一块。 */
@Composable
internal fun FilePreviewPage(
    path: String,
    body: String,
    onDismiss: () -> Unit,
    onQuote: ((String) -> Unit)?,
) {
    val context = LocalContext.current
    val copiedNotice = DshS.filePathCopied
    val subtitle = remember(path, body) {
        val dir = path.trim().substringBeforeLast('/', missingDelimiterValue = "")
        listOfNotNull(dir.ifEmpty { null }, formatFileSize(body.encodeToByteArray().size.toLong())).joinToString(" · ")
    }
    Column(Modifier.fillMaxSize().background(Dsh.bgBase).systemBarsPadding()) {
        DlTopBar(
            title = producedFileName(path),
            subtitle = subtitle,
            nav = DlTopBarNav.Back,
            onNav = onDismiss,
            showDivider = true,
        )
        Box(Modifier.weight(1f).fillMaxWidth().horizontalScroll(rememberScrollState())) {
            SelectionContainer {
                Text(
                    body,
                    color = Dsh.labelPrimary,
                    style = DshType.supporting.copy(fontFamily = FontFamily.Monospace),
                    softWrap = false,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = DshSpace.s16, vertical = DshSpace.s12),
                )
            }
        }
        HorizontalDivider(color = Dsh.outline)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpace.s8, vertical = DshSpace.s8),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            DlButton(
                DlAction(DshS.fileCopyPath, {
                    copyWorkspacePath(context, path)
                    Toast.makeText(context, copiedNotice, Toast.LENGTH_SHORT).show()
                }, DlButtonStyle.Text),
                compact = true,
            )
            DlButton(DlAction(DshS.fileShare, { shareFileText(context, path, body) }, DlButtonStyle.Text), compact = true)
            if (onQuote != null) {
                DlButton(
                    DlAction(DshS.fileQuote, {
                        onQuote(path)
                        onDismiss()
                    }, DlButtonStyle.Text),
                    compact = true,
                )
            }
        }
    }
}

internal fun copyWorkspacePath(context: Context, path: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("workspace path", path))
}

private fun shareFileText(context: Context, path: String, body: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, producedFileName(path))
        putExtra(Intent.EXTRA_TEXT, body)
    }
    context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
