package dev.deeplinks.native.ui.v4

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.native.DshRadius
import dev.deeplinks.native.DshSpace
import dev.deeplinks.native.DshTouch

/**
 * v4 收件箱条目（2.1）：状态点 + 状态 · 工作区 + 时间；标题；命令或问题预览；
 * 等你处理的条目带内联按钮（拒绝 / 允许一次 / 回答），主按钮放最后。
 * [running] 在预览前加转圈。[onLongClick] 打开 2.6 长按菜单。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DlInboxItem(
    workspace: String,
    time: String,
    title: String,
    modifier: Modifier = Modifier,
    status: String? = null,
    tone: DlTone = DlTone.Neutral,
    preview: String? = null,
    command: String? = null,
    running: Boolean = false,
    actions: List<DlAction> = emptyList(),
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    /** 2.4 搜索：标题和预览里命中的词用品牌色。 */
    highlight: String? = null,
    /** 2.1 工作区内的紧凑会话：标题优先，不重复显示工作区。 */
    compact: Boolean = false,
) {
    val hit = Dsh.brand400
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DshTouch.min)
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(onLongClick = onLongClick, onClick = { onClick?.invoke() })
                } else {
                        Modifier
                    },
                )
                .padding(horizontal = DshSpace.s20, vertical = DshSpace.s12),
            verticalArrangement = Arrangement.spacedBy(DshSpace.s4),
        ) {
            if (compact) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DshSpace.s8)) {
                    Text(dlHighlighted(title, highlight, hit), style = DshType.body, color = Dsh.labelPrimary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (time.isNotBlank()) Text(time, style = DshType.caption, color = Dsh.tertiaryText, maxLines = 1)
                }
                if (status != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DshSpace.s8)) {
                        if (running) DlSpinner() else DlStatusDot(tone)
                        Text(status, style = DshType.caption, color = if (running) Dsh.labelSecondary else tone.color)
                    }
                }
            } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(DshSpace.s12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(DshSpace.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (status != null) {
                        DlStatusDot(tone)
                        Text(status, style = DlLabelStrong, color = tone.color, maxLines = 1)
                        Text("·", style = DshType.supporting, color = Dsh.labelSecondary)
                    }
                    Text(
                        workspace,
                        style = DshType.supporting,
                        color = Dsh.labelSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Text(time, style = DshType.caption, color = Dsh.tertiaryText, maxLines = 1)
            }
            Text(dlHighlighted(title, highlight, hit), style = DshType.bodyStrong, color = Dsh.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (preview != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(DshSpace.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (running) DlSpinner()
                Text(dlHighlighted(preview, highlight, hit), style = DshType.supporting, color = Dsh.labelSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (command != null) {
            Text(
                command,
                style = DshType.caption.copy(fontFamily = FontFamily.Monospace),
                color = Dsh.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = DshSpace.s4)
                    .fillMaxWidth()
                    .background(Dsh.surface1, RoundedCornerShape(DshRadius.control))
                    .padding(horizontal = DshSpace.s12, vertical = DshSpace.s8),
            )
        }
        if (actions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = DshSpace.s8),
                horizontalArrangement = Arrangement.spacedBy(DshSpace.s8, Alignment.End),
            ) {
                for (action in actions) DlButton(action, compact = true)
            }
        }
    }
}

/** 把 [needle] 的每次出现（忽略大小写）染成 [color]。 */
internal fun dlHighlighted(text: String, needle: String?, color: Color): AnnotatedString {
    if (needle.isNullOrBlank()) return AnnotatedString(text)
    return buildAnnotatedString {
        var from = 0
        while (from < text.length) {
            val at = text.indexOf(needle, from, ignoreCase = true)
            if (at < 0) break
            append(text.substring(from, at))
            withStyle(SpanStyle(color = color)) { append(text.substring(at, at + needle.length)) }
            from = at + needle.length
        }
        if (from < text.length) append(text.substring(from))
    }
}
