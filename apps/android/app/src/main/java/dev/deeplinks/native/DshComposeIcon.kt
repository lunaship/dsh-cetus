package dev.deeplinks.native

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 新建（方框 + 笔，iOS / Kurage 那种「写一条」）：首页文件夹行尾与底部新任务按钮用，比 [EditOutline16] 轻。 */
val ComposeOutline16: ImageVector by lazy {
    ImageVector.Builder(
        name = "ComposeOutline16",
        defaultWidth = 16.0f.dp,
        defaultHeight = 16.dp,
        viewportWidth = 16.0f,
        viewportHeight = 16.0f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.25f,
            strokeLineJoin = StrokeJoin.Round,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(7.25f, 2.25f)
            lineTo(4.0f, 2.25f)
            quadTo(2.25f, 2.25f, 2.25f, 4.0f)
            lineTo(2.25f, 12.0f)
            quadTo(2.25f, 13.75f, 4.0f, 13.75f)
            lineTo(12.0f, 13.75f)
            quadTo(13.75f, 13.75f, 13.75f, 12.0f)
            lineTo(13.75f, 8.75f)
            moveTo(12.1f, 1.9f)
            lineTo(14.1f, 3.9f)
            lineTo(8.25f, 9.75f)
            lineTo(5.75f, 10.25f)
            lineTo(6.25f, 7.75f)
            close()
        }
    }.build()
}
