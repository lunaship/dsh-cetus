package dev.deeplinks.native.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.DshType
import dev.deeplinks.native.ArrowLeftOutline16

/**
 * 一级页面统一骨架（docs/visual-rules.md 第一节）。
 *
 * 页面不得自行决定标题、边距和背景：所有一级页面都是
 * 「页面导航区（返回/设备上下文 · 标题 · 页面动作）+ 页面内容」，
 * 画布固定 [Dsh.bgBase]，Compact 水平边距 16dp，Medium/Expanded 内容
 * 最大宽度 [maxContentWidth]（聊天工作区与双栏布局不受此限制）。
 */

/** 页面导航区左侧内容：无 / 返回按钮。 */
enum class DshPageNavigation {
    None,
    Back,
}

private val PageNavHeight = 56.dp

@Composable
fun DshPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    navigation: DshPageNavigation = DshPageNavigation.None,
    onNavigateBack: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    maxContentWidth: Dp = 720.dp,
    /**
     * 是否由本骨架消费系统栏 inset。迁移期兼容包装（[DshGroupedPage]）传 false——
     * 它的调用方（设置 / 设备页）自己已 padding 过一次；批次 2/3 直接采用本骨架后
     * 一律使用默认值 true，此参数随之删除。
     */
    consumeSystemInsets: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Dsh.bgBase),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = maxContentWidth)
                .fillMaxWidth()
                .then(
                    if (consumeSystemInsets) {
                        Modifier.statusBarsPadding().navigationBarsPadding()
                    } else {
                        Modifier
                    },
                ),
        ) {
            // 标题为空（迁移期兼容包装）时不画导航区，由调用方自己的顶栏负责
            if (title.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = PageNavHeight)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (navigation == DshPageNavigation.Back) {
                        DshIconAction(
                            icon = ArrowLeftOutline16,
                            contentDescription = DshS.back,
                            onClick = onNavigateBack,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        title,
                        color = Dsh.labelPrimary,
                        style = DshType.headlineMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = if (navigation == DshPageNavigation.None) 12.dp else 0.dp)
                            .semantics { heading() },
                    )
                    actions()
                }
            }
            content()
        }
    }
}
