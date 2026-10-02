package dev.deeplinks.native.ui.v4

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.native.DshSheetShape
import dev.deeplinks.native.DshSpace

/** 弹层底色：浅色画布白，深色容器色（与画布拉开一档）。 */
internal val DlOverlayColor: Color
    @Composable @ReadOnlyComposable
    get() = if (Dsh.isDark) Dsh.surface1 else Dsh.bgBase

/** 弹层里的嵌入块（预览卡、大按钮、搜索框）底色：比 [DlOverlayColor] 高一级。 */
internal val DlInsetColor: Color
    @Composable @ReadOnlyComposable
    get() = if (Dsh.isDark) Dsh.surface2 else Dsh.surface1

/**
 * v4 底部弹层（5.x）：包 M3 [ModalBottomSheet]，顶角 28dp、scrim 分层、无阴影。
 * 标题 17sp + 可选副标题。不要在弹层里再打开第二层弹层；需要确认时用 [DlDialog]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DlBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = DshSheetShape,
        containerColor = DlOverlayColor,
        contentColor = Dsh.labelPrimary,
        tonalElevation = 0.dp,
        scrimColor = Dsh.bgOverlay,
        dragHandle = { DlSheetHandle() },
    ) {
        DlBottomSheetBody(title, subtitle, content)
    }
}

/** 弹层的静态外观（把手 + 标题 + 内容），供截图测试与 [DlBottomSheet] 共用。 */
@Composable
fun DlBottomSheetSurface(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().background(DlOverlayColor, DshSheetShape)) {
        DlSheetHandle()
        DlBottomSheetBody(title, subtitle, content)
    }
}

@Composable
private fun DlSheetHandle() {
    Box(Modifier.fillMaxWidth().padding(top = DshSpace.s12, bottom = DshSpace.s8), contentAlignment = Alignment.Center) {
        Box(Modifier.size(DlSize.handleWidth, DlSize.handleHeight).background(Dsh.outline, DlPill))
    }
}

@Composable
private fun DlBottomSheetBody(
    title: String?,
    subtitle: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = DshSpace.s12)) {
        if (title != null) {
            Column(Modifier.padding(start = DshSpace.s24, end = DshSpace.s24, top = DshSpace.s4, bottom = DshSpace.s12)) {
                Text(title, style = DshType.titleLarge, color = Dsh.labelPrimary, modifier = Modifier.semantics { heading() })
                if (subtitle != null) Text(subtitle, style = DshType.supporting, color = Dsh.labelSecondary)
            }
        }
        content()
    }
}
