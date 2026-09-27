package dev.deeplinks.native.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.deeplinks.R
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.native.DshRadius
import dev.deeplinks.native.DshTileShape

/** 品牌 mark：空会话、无配对等首屏共用一枚——图标底板 + 弱品牌底 + 发丝描边。 */
@Composable
fun DshBrandMark(modifier: Modifier = Modifier, size: Dp = 72.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(DshTileShape)
            .background(Dsh.brand400.copy(alpha = 0.12f))
            .border(1.dp, Dsh.brand400.copy(alpha = 0.28f), DshTileShape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_dsh_mark),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 空态 / 错误态的唯一模板：图形 → 标题 → 说明 → 操作 → 脚注，整体居中。
 *
 * - 整屏（默认）：标题 headlineMedium，操作是按内容宽度的实心胶囊按钮——空会话、会话加载失败、无配对电脑；
 * - [compact]：嵌在列表或弹层里（侧栏、模型列表），标题 titleLarge，操作降为品牌色文字按钮，
 *   不喧宾夺主。
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
            style = if (compact) DshType.titleLarge else DshType.headlineMedium,
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
            if (compact) {
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(DshRadius.full))
                        .clickable(role = Role.Button, onClick = onAction)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(actionLabel, color = Dsh.brand400, style = DshType.labelLarge)
                }
            } else {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(DshRadius.full),
                    colors = ButtonDefaults.buttonColors(containerColor = Dsh.brand400, contentColor = Dsh.onBrand),
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
