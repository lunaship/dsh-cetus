package dev.deeplinks.native.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.native.DshRadius
import dev.deeplinks.native.WarningOutline16

/**
 * 三种空态共用的排版骨架：图形 → 标题 → 说明 → 操作 → 脚注，整体居中。
 * 语义差异由三个公开入口决定（见下），骨架本身不表达情绪。
 */
@Composable
private fun DshStateScaffold(
    title: String,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle,
    message: String? = null,
    graphic: (@Composable () -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    footnote: String? = null,
    compact: Boolean = false,
    /** 错误态的主操作用实心 CTA；空数据态用文字操作，不喧宾夺主。 */
    solidAction: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = if (compact) 16.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (graphic != null) {
            graphic()
            Spacer(Modifier.height(20.dp))
        }
        Text(
            title,
            color = Dsh.labelPrimary,
            style = titleStyle,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                color = Dsh.labelTertiary,
                style = DshType.body,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
        if (actionLabel != null && onAction != null) {
            if (compact || !solidAction) {
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(DshRadius.full))
                        .clickable(role = Role.Button, onClick = onAction)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(actionLabel, color = Dsh.labelPrimary, style = DshType.labelLarge)
                }
            } else {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(DshRadius.full),
                    colors = ButtonDefaults.buttonColors(containerColor = Dsh.brand500, contentColor = Dsh.onBrand),
                    contentPadding = PaddingValues(horizontal = 28.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(actionLabel, style = DshType.labelLarge)
                }
            }
        }
        if (footnote != null) {
            Spacer(Modifier.height(10.dp))
            Text(footnote, color = Dsh.labelTertiary, style = DshType.captionRelaxed, textAlign = TextAlign.Center)
        }
    }
}

/**
 * 空数据态：中性线性图标 + 标题 + 说明 + 可选文字操作。
 * 标题降一级，不与页面主任务争夺视觉焦点；主要引导交给 Composer
 * placeholder 与附件入口，不放大尺寸情绪插画。
 */
@Composable
fun DshEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    graphic: (@Composable () -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    footnote: String? = null,
    compact: Boolean = false,
) {
    DshStateScaffold(
        title = title,
        modifier = modifier,
        titleStyle = DshType.titleLarge,
        message = message,
        graphic = graphic,
        actionLabel = actionLabel,
        onAction = onAction,
        footnote = footnote,
        compact = compact,
        solidAction = false,
    )
}

/**
 * 错误态：错误图标 + 简短错误 + 重试动作。
 * 不得使用欢迎插画；颜色只是辅助，重试动作与错误文案承担语义。
 */
@Composable
fun DshErrorState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    graphic: (@Composable () -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    footnote: String? = null,
    compact: Boolean = false,
) {
    DshStateScaffold(
        title = title,
        modifier = modifier,
        titleStyle = if (compact) DshType.titleLarge else DshType.titleLarge,
        message = message,
        graphic = graphic ?: { DshErrorGraphic() },
        actionLabel = actionLabel,
        onAction = onAction,
        footnote = footnote,
        compact = compact,
        solidAction = true,
    )
}

/** 错误态默认图形：errorBg 圆底 + 错误图标（40dp，克制）。 */
@Composable
private fun DshErrorGraphic() {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Dsh.errorBg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            WarningOutline16,
            contentDescription = null,
            tint = Dsh.error,
            modifier = Modifier.size(20.dp),
        )
    }
}
