package dev.deeplinks.native

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.core.selectTextQuote
import dev.deeplinks.native.ui.v4.DlAction
import dev.deeplinks.native.ui.v4.DlButton
import dev.deeplinks.native.ui.v4.DlButtonStyle
import dev.deeplinks.native.ui.v4.DlTopBar
import dev.deeplinks.native.ui.v4.DlTopBarNav

/**
 * 5.14 选择文字：长按消息进入的全屏页，系统选择手柄自由选；底部「复制」「引用到输入框」。
 * 不把 SelectionContainer 嵌进会话 LazyColumn，避免和滚动、长按菜单抢手势。
 */
@Composable
fun SelectTextDialog(
    text: String,
    onDismiss: () -> Unit,
    onCopy: (() -> Unit)? = null,
    onQuote: (() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        SelectTextPage(text, onDismiss, onCopy, onQuote)
    }
}

/** 页面本体，截图直接画这一块。 */
@Composable
internal fun SelectTextPage(
    text: String,
    onDismiss: () -> Unit,
    onCopy: (() -> Unit)?,
    onQuote: (() -> Unit)?,
) {
    Column(Modifier.fillMaxSize().background(Dsh.bgBase).systemBarsPadding()) {
        DlTopBar(title = L.selectText, nav = DlTopBarNav.Close, onNav = onDismiss)
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                text,
                color = Dsh.labelPrimary,
                style = DshType.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = DshSpace.s20, vertical = DshSpace.s12),
            )
        }
        if (onCopy != null || onQuote != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpace.s16, vertical = DshSpace.s12),
                horizontalArrangement = Arrangement.spacedBy(DshSpace.s8),
            ) {
                if (onCopy != null) {
                    DlButton(
                        DlAction(L.copy, {
                            onCopy()
                            onDismiss()
                        }, DlButtonStyle.Filled),
                    )
                }
                if (onQuote != null) {
                    DlButton(
                        DlAction(DshS.selectTextQuote, {
                            onQuote()
                            onDismiss()
                        }),
                    )
                }
            }
        }
    }
}
