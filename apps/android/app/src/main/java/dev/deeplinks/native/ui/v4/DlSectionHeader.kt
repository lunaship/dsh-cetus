package dev.deeplinks.native.ui.v4

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.tabularNums
import dev.deeplinks.native.DshSpace

/**
 * v4 组头（7.1、2.1）：13sp 600 次要色；右侧可放计数（品牌色）或文字按钮。
 */
@Composable
fun DlSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    onTrailingClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = DshSpace.s20, end = DshSpace.s20, top = DshSpace.s20, bottom = DshSpace.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = DlLabelStrong,
            color = Dsh.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (trailing != null) {
            Text(
                trailing,
                style = DlLabelStrong.copy(fontWeight = FontWeight.Medium).tabularNums(),
                color = Dsh.brand400,
                maxLines = 1,
                modifier = if (onTrailingClick != null) Modifier.clickable(onClick = onTrailingClick) else Modifier,
            )
        }
    }
}
