package dev.deeplinks.native.ui.v4

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.native.DshRadius
import dev.deeplinks.native.DshSpace

/** 状态语气：只表达状态，不做装饰（docs/visual-rules.md §2）。 */
enum class DlTone { Neutral, Brand, Wait, Ok, Err, Off }

internal val DlTone.color: Color
    @Composable @ReadOnlyComposable
    get() = when (this) {
        DlTone.Neutral -> Dsh.labelSecondary
        DlTone.Brand -> Dsh.brand400
        DlTone.Wait -> Dsh.wait
        DlTone.Ok -> Dsh.ok
        DlTone.Err -> Dsh.err
        DlTone.Off -> Dsh.tertiaryText
    }

internal val DlPill = RoundedCornerShape(DshRadius.full)

/** 13sp 600：组头、状态标签。 */
internal val DlLabelStrong
    @Composable @ReadOnlyComposable
    get() = DshType.titleSmall.copy(fontWeight = FontWeight.SemiBold)

@Composable
internal fun DlStatusDot(tone: DlTone, modifier: Modifier = Modifier) {
    Box(modifier.size(DshSpace.s8).background(tone.color, CircleShape))
}

@Composable
internal fun DlSpinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        modifier = modifier.size(DshSpace.s16),
        color = Dsh.brand400,
        trackColor = Dsh.primarySoft,
        strokeWidth = 2.dp,
    )
}

/** v4 按钮的四种样式：品牌实心（每屏最多一个）、容器色、文字、危险文字。 */
enum class DlButtonStyle { Filled, Tonal, Text, Danger }

/** 一个按钮动作：组件参数用，页面不直接画按钮。 */
data class DlAction(
    val label: String,
    val onClick: () -> Unit,
    val style: DlButtonStyle = DlButtonStyle.Tonal,
    val enabled: Boolean = true,
)

@Composable
internal fun DlButton(
    action: DlAction,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val height = if (compact) DlSize.buttonCompact else DlSize.button
    val padding = PaddingValues(horizontal = if (compact) DshSpace.s16 else DshSpace.s20)
    val textStyle = if (compact) DlLabelStrong else DshType.bodyStrong
    when (action.style) {
        DlButtonStyle.Text, DlButtonStyle.Danger -> TextButton(
            onClick = action.onClick,
            enabled = action.enabled,
            shape = DlPill,
            contentPadding = PaddingValues(horizontal = DshSpace.s12),
            colors = ButtonDefaults.textButtonColors(
                contentColor = if (action.style == DlButtonStyle.Danger) Dsh.err else Dsh.brand400,
                disabledContentColor = Dsh.tertiaryText,
            ),
            modifier = modifier.height(height),
        ) { DlButtonLabel(action.label, textStyle) }
        DlButtonStyle.Filled, DlButtonStyle.Tonal -> {
            val filled = action.style == DlButtonStyle.Filled
            Button(
                onClick = action.onClick,
                enabled = action.enabled,
                shape = DlPill,
                contentPadding = padding,
                elevation = null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (filled) Dsh.brand400 else Dsh.surface1,
                    contentColor = if (filled) Dsh.onBrand else Dsh.labelPrimary,
                    disabledContainerColor = Dsh.surface2,
                    disabledContentColor = Dsh.tertiaryText,
                ),
                modifier = modifier.height(height),
            ) { DlButtonLabel(action.label, textStyle) }
        }
    }
}

@Composable
private fun DlButtonLabel(text: String, style: androidx.compose.ui.text.TextStyle) {
    Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** v4 组件尺寸（docs/visual-rules.md §4）。 */
internal object DlSize {
    val topBar = 56.dp
    val topBarLarge = 64.dp
    val rowSingle = 52.dp
    val rowDouble = 64.dp
    val button = 48.dp
    val buttonCompact = 36.dp
    val chip = 32.dp
    val send = 36.dp
    val handleWidth = 36.dp
    val handleHeight = 4.dp
}
