package dev.deeplinks.native.ui

import dev.deeplinks.native.CloseOutline16
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.native.DshRadius
import dev.deeplinks.native.DshSheetShape

/**
 * 统一底部面板（docs/visual-rules.md 第五节）：画布底 + 把手 + 标题 / 说明（+ 可选关闭钮）。
 * 面板里的选项列表用 [DshListSection]，和设置页同一套行；形状统一走 [DshSheetShape]
 * （modal 28dp），scrim 用 [Dsh.bgOverlay]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DshSheet(
    onDismiss: () -> Unit,
    title: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showClose: Boolean = false,
    skipPartiallyExpanded: Boolean = false,
    headerTrailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
        containerColor = Dsh.bgBase,
        contentColor = Dsh.labelPrimary,
        shape = DshSheetShape,
        scrimColor = Dsh.bgOverlay,
        dragHandle = null,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
        ) {
            DshSheetGrabber()
            if (title != null) {
                DshSheetHeader(
                    title = title,
                    subtitle = subtitle,
                    onClose = if (showClose) onDismiss else null,
                    trailing = headerTrailing,
                )
            }
            content()
        }
    }
}

@Composable
fun DshSheetHeader(
    title: String,
    subtitle: String? = null,
    onClose: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            Text(
                title,
                color = Dsh.labelPrimary,
                style = DshType.headline,
                modifier = Modifier.semantics { heading() },
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = Dsh.labelTertiary, style = DshType.supporting)
            }
        }
        trailing?.invoke()
        if (onClose != null) DshCloseButton(onClose)
    }
}

/** 面板底部的整宽主按钮（提交 / 保存）。 */
@Composable
fun DshSheetPrimaryButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(DshRadius.full),
        colors = ButtonDefaults.buttonColors(
            // 实心主操作统一 brand500（brand400 底配白字在暗色下不达 AA）
            containerColor = if (danger) Dsh.error else Dsh.brand500,
            contentColor = Dsh.onBrand,
            disabledContainerColor = Dsh.bgTrack,
            disabledContentColor = Dsh.labelTertiary,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .heightIn(min = 50.dp),
    ) {
        Text(label, style = DshType.labelLarge)
    }
}

/** 圆形关闭钮：30dp 视觉 / 48dp 热区（对照 lody 的 LodyCloseButton）。 */
@Composable
fun DshCloseButton(onClick: () -> Unit) {
    val s = DshS
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = s.close },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Dsh.bgSubtle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CloseOutline16, contentDescription = null, tint = Dsh.labelSecondary, modifier = Modifier.size(16.dp))
        }
    }
}
