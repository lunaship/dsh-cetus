package dev.deeplinks.native

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 自绘补充图标：补齐 Web 复刻集（DshIcons.kt）没覆盖的语义，App 内不再用 Material Icons。
 *
 * 笔法与 Web 复刻集一致：16 格满幅、线宽 1.35、直角线头 + 圆角转折，点状细节用实心圆。
 * 路径是 SVG path 语法，改图标直接改字符串；新增图标也写在这里，不要再引入第三套图标。
 */

private const val GLYPH_STROKE = 1.35f

private fun glyph(name: String, strokes: List<String>, fills: List<String> = emptyList()): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 16.dp,
        defaultHeight = 16.dp,
        viewportWidth = 16f,
        viewportHeight = 16f,
    ).apply {
        strokes.forEach { d ->
            addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = GLYPH_STROKE,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        fills.forEach { d -> addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black)) }
    }.build()

val ArchiveBoxOutline16: ImageVector by lazy {
    glyph(
        "ArchiveBoxOutline16",
        listOf(
            "M1.68 1.2H14.32a1 1 0 0 1 1 1V4a1 1 0 0 1 -1 1H1.68a1 1 0 0 1 -1 -1V2.2a1 1 0 0 1 1 -1Z",
            "M1.9 5v8.2a2 2 0 0 0 2 2h8.2a2 2 0 0 0 2-2V5",
            "M6 8.4H10",
        ),
    )
}

val ArrowLeftOutline16: ImageVector by lazy {
    glyph(
        "ArrowLeftOutline16",
        listOf(
            "M15.2 8H1.4",
            "M7.1 2.2 1.3 8l5.8 5.8",
        ),
    )
}

val CameraOutline16: ImageVector by lazy {
    glyph(
        "CameraOutline16",
        listOf(
            "M0.68 5.3a1.6 1.6 0 0 1 1.6-1.6H4.4L5.7 1.6H10.3l1.3 2.1h2.1a1.6 1.6 0 0 1 1.6 1.6v7.5a1.6 1.6 0 0 1-1.6 1.6H2.3a1.6 1.6 0 0 1-1.6-1.6Z",
            "M5.2 8.7a2.8 2.8 0 1 0 5.6 0a2.8 2.8 0 1 0 -5.6 0Z",
        ),
    )
}

val ClockOutline16: ImageVector by lazy {
    glyph(
        "ClockOutline16",
        listOf(
            "M0.67 8a7.33 7.33 0 1 0 14.65 0a7.33 7.33 0 1 0 -14.65 0Z",
            "M8 3.9V8l2.9 1.8",
        ),
    )
}

val CompressOutline16: ImageVector by lazy {
    glyph(
        "CompressOutline16",
        listOf(
            "M1.2 14.8 6.2 9.8",
            "M2.6 9.8H6.2V13.4",
            "M14.8 1.2 9.8 6.2",
            "M9.8 2.6V6.2H13.4",
        ),
    )
}

val ContrastOutline16: ImageVector by lazy {
    glyph(
        "ContrastOutline16",
        listOf(
            "M0.67 8a7.33 7.33 0 1 0 14.65 0a7.33 7.33 0 1 0 -14.65 0Z",
        ),
        listOf(
            "M8 0.7a7.3 7.3 0 0 1 0 14.6Z",
        ),
    )
}

val DevicesOutline16: ImageVector by lazy {
    glyph(
        "DevicesOutline16",
        listOf(
            "M1.98 1.8H9a1.3 1.3 0 0 1 1.3 1.3V9.3a1.3 1.3 0 0 1 -1.3 1.3H1.98a1.3 1.3 0 0 1 -1.3 -1.3V3.1a1.3 1.3 0 0 1 1.3 -1.3Z",
            "M5.5 10.6V14",
            "M3.2 14H7.8",
            "M12.9 5.2H14.22a1.1 1.1 0 0 1 1.1 1.1V13.1a1.1 1.1 0 0 1 -1.1 1.1H12.9a1.1 1.1 0 0 1 -1.1 -1.1V6.3a1.1 1.1 0 0 1 1.1 -1.1Z",
        ),
    )
}

val EraserOutline16: ImageVector by lazy {
    glyph(
        "EraserOutline16",
        listOf(
            "M9.5 1.7l4.8 4.8a1.4 1.4 0 0 1 0 2L8.6 14.2H4.9L1.7 11a1.4 1.4 0 0 1 0-2L7.5 1.7a1.4 1.4 0 0 1 2 0Z",
            "M4.9 4.4l6.7 6.7",
            "M8.6 14.3H15.3",
        ),
    )
}

val FeedbackOutline16: ImageVector by lazy {
    glyph(
        "FeedbackOutline16",
        listOf(
            "M2.3 1.5H13.7a1.6 1.6 0 0 1 1.6 1.6v7.6a1.6 1.6 0 0 1-1.6 1.6H6.4L3.1 14.9V12.3H2.3a1.6 1.6 0 0 1-1.6-1.6V3.1a1.6 1.6 0 0 1 1.6-1.6Z",
            "M8 4.2V7.5",
        ),
        listOf(
            "M7.15 9.6a0.85 0.85 0 1 0 1.7 0a0.85 0.85 0 1 0 -1.7 0Z",
        ),
    )
}

val FileOutline16: ImageVector by lazy {
    glyph(
        "FileOutline16",
        listOf(
            "M9.4 0.7H3.3A1.6 1.6 0 0 0 1.7 2.3V13.7a1.6 1.6 0 0 0 1.6 1.6H12.7a1.6 1.6 0 0 0 1.6-1.6V5.6Z",
            "M9.4 0.7V5.6H14.3",
            "M4.9 9H11.1",
            "M4.9 12H8.9",
        ),
    )
}

val FontOutline16: ImageVector by lazy {
    glyph(
        "FontOutline16",
        listOf(
            "M0.8 14.2 4.8 2.6 8.8 14.2",
            "M2.2 10.3H7.4",
            "M9.5 11.3a2.6 2.6 0 1 0 5.2 0a2.6 2.6 0 1 0 -5.2 0Z",
            "M14.7 8.1V14.2",
        ),
    )
}

val GiftOutline16: ImageVector by lazy {
    glyph(
        "GiftOutline16",
        listOf(
            "M1.68 4.1H14.32a1 1 0 0 1 1 1V6.4a1 1 0 0 1 -1 1H1.68a1 1 0 0 1 -1 -1V5.1a1 1 0 0 1 1 -1Z",
            "M1.9 7.4V13.9a1.4 1.4 0 0 0 1.4 1.4H12.7a1.4 1.4 0 0 0 1.4-1.4V7.4",
            "M8 4.1V15.3",
            "M8 4.1C7 1.3 4.3 0.6 3.9 2.3 3.6 3.7 5.9 4.1 8 4.1Z",
            "M8 4.1C9 1.3 11.7 0.6 12.1 2.3 12.4 3.7 10.1 4.1 8 4.1Z",
        ),
    )
}

val ImageOutline16: ImageVector by lazy {
    glyph(
        "ImageOutline16",
        listOf(
            "M3.28 0.68H12.72a2.6 2.6 0 0 1 2.6 2.6V12.72a2.6 2.6 0 0 1 -2.6 2.6H3.28a2.6 2.6 0 0 1 -2.6 -2.6V3.28a2.6 2.6 0 0 1 2.6 -2.6Z",
            "M1.1 12.6 5.6 8.3l3.3 3.1 2.3-2.1 3.9 3.6",
        ),
        listOf(
            "M3.75 5a1.35 1.35 0 1 0 2.7 0a1.35 1.35 0 1 0 -2.7 0Z",
        ),
    )
}

val InfoOutline16: ImageVector by lazy {
    glyph(
        "InfoOutline16",
        listOf(
            "M0.67 8a7.33 7.33 0 1 0 14.65 0a7.33 7.33 0 1 0 -14.65 0Z",
            "M8 6.9V12",
        ),
        listOf(
            "M7.1 4.4a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0 -1.8 0Z",
        ),
    )
}

val KeyOutline16: ImageVector by lazy {
    glyph(
        "KeyOutline16",
        listOf(
            "M0.8 11.3a3.9 3.9 0 1 0 7.8 0a3.9 3.9 0 1 0 -7.8 0Z",
            "M7.5 8.5 14.9 1.1",
            "M12.4 3.6l2 2",
            "M10.2 5.8l1.6 1.6",
        ),
    )
}

val KeyboardOutline16: ImageVector by lazy {
    glyph(
        "KeyboardOutline16",
        listOf(
            "M2.67 2.8H13.32a2 2 0 0 1 2 2V11.2a2 2 0 0 1 -2 2H2.67a2 2 0 0 1 -2 -2V4.8a2 2 0 0 1 2 -2Z",
            "M4.6 10H11.4",
        ),
        listOf(
            "M3.1 6.3a0.8 0.8 0 1 0 1.6 0a0.8 0.8 0 1 0 -1.6 0Z",
            "M5.8 6.3a0.8 0.8 0 1 0 1.6 0a0.8 0.8 0 1 0 -1.6 0Z",
            "M8.6 6.3a0.8 0.8 0 1 0 1.6 0a0.8 0.8 0 1 0 -1.6 0Z",
            "M11.3 6.3a0.8 0.8 0 1 0 1.6 0a0.8 0.8 0 1 0 -1.6 0Z",
        ),
    )
}

val LaptopOutline16: ImageVector by lazy {
    glyph(
        "LaptopOutline16",
        listOf(
            "M3.6 2.3H12.4a1.4 1.4 0 0 1 1.4 1.4V9.5a1.4 1.4 0 0 1 -1.4 1.4H3.6a1.4 1.4 0 0 1 -1.4 -1.4V3.7a1.4 1.4 0 0 1 1.4 -1.4Z",
            "M0.7 13.9H15.3",
        ),
    )
}

val MessageOutline16: ImageVector by lazy {
    glyph(
        "MessageOutline16",
        listOf(
            "M2.3 1.5H13.7a1.6 1.6 0 0 1 1.6 1.6v7.6a1.6 1.6 0 0 1-1.6 1.6H6.4L3.1 14.9V12.3H2.3a1.6 1.6 0 0 1-1.6-1.6V3.1a1.6 1.6 0 0 1 1.6-1.6Z",
        ),
    )
}

val MicOutline16: ImageVector by lazy {
    glyph(
        "MicOutline16",
        listOf(
            "M8 0.7H8a3 3 0 0 1 3 3V7.1a3 3 0 0 1 -3 3H8a3 3 0 0 1 -3 -3V3.7a3 3 0 0 1 3 -3Z",
            "M2.5 7.6a5.5 5.5 0 0 0 11 0",
            "M8 13.1V15.3",
        ),
    )
}

val PaletteOutline16: ImageVector by lazy {
    glyph(
        "PaletteOutline16",
        listOf(
            "M8 0.7C4 0.7 0.7 4 0.7 8c0 4 3.2 7.3 7 7.3 1.2 0 1.8-0.8 1.4-1.8-0.5-1.2 0.2-2.4 1.6-2.4h1.6c1.7 0 3-1.3 3-3.1C15.3 4 12 0.7 8 0.7Z",
        ),
        listOf(
            "M3.4 8.4a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0 -1.8 0Z",
            "M4.4 4.8a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0 -1.8 0Z",
            "M8 3.8a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0 -1.8 0Z",
            "M10.9 5.9a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0 -1.8 0Z",
        ),
    )
}

val QuoteOutline16: ImageVector by lazy {
    glyph(
        "QuoteOutline16",
        listOf(
            "M1.6 1.8V14.2",
            "M5.4 3.6H15.2",
            "M5.4 8H15.2",
            "M5.4 12.4H11.6",
        ),
    )
}

val ScanOutline16: ImageVector by lazy {
    glyph(
        "ScanOutline16",
        listOf(
            "M0.68 4.6V2.7a2 2 0 0 1 2-2H4.6",
            "M11.4 0.68h1.9a2 2 0 0 1 2 2V4.6",
            "M15.32 11.4v1.9a2 2 0 0 1-2 2H11.4",
            "M4.6 15.32H2.7a2 2 0 0 1-2-2V11.4",
            "M3.6 8H12.4",
        ),
    )
}

val ShieldOutline16: ImageVector by lazy {
    glyph(
        "ShieldOutline16",
        listOf(
            "M8 0.8 14.2 3.1V7.7c0 3.6-2.5 6.2-6.2 7.5C4.3 13.9 1.8 11.3 1.8 7.7V3.1Z",
            "M5.4 7.9 7.3 9.8 10.8 6.3",
        ),
    )
}

val SparkleOutline16: ImageVector by lazy {
    glyph(
        "SparkleOutline16",
        listOf(
            "M6.3 3.2Q6.8 8.1 11.8 8.7 6.8 9.3 6.3 14.3 5.8 9.3 0.8 8.7 5.8 8.1 6.3 3.2Z",
        ),
        listOf(
            "M12.6 0.7Q12.9 3 15.3 3.3 12.9 3.6 12.6 6 12.3 3.6 9.9 3.3 12.3 3 12.6 0.7Z",
        ),
    )
}

val SwapOutline16: ImageVector by lazy {
    glyph(
        "SwapOutline16",
        listOf(
            "M1 4.9H14.4",
            "M10.9 1.4 14.4 4.9 10.9 8.4",
            "M15 11.1H1.6",
            "M5.1 7.6 1.6 11.1l3.5 3.5",
        ),
    )
}

val TextSizeOutline16: ImageVector by lazy {
    glyph(
        "TextSizeOutline16",
        listOf(
            "M0.9 2.6H10.1",
            "M5.5 2.6V14.4",
            "M9.4 7.8H15.3",
            "M12.35 7.8V14.4",
        ),
    )
}

val TranslateOutline16: ImageVector by lazy {
    glyph(
        "TranslateOutline16",
        listOf(
            "M0.9 3.3H9.1",
            "M5 0.8V3.3",
            "M7.3 3.3C6.7 6.4 4.4 9.1 1.2 10.7",
            "M3.3 5.9C4.3 7.7 5.9 9.1 7.8 10",
            "M8.4 15.3 11.7 7.4 15 15.3",
            "M9.6 12.6H13.8",
        ),
    )
}

val UnarchiveOutline16: ImageVector by lazy {
    glyph(
        "UnarchiveOutline16",
        listOf(
            "M1.68 1.2H14.32a1 1 0 0 1 1 1V4a1 1 0 0 1 -1 1H1.68a1 1 0 0 1 -1 -1V2.2a1 1 0 0 1 1 -1Z",
            "M1.9 5v8.2a2 2 0 0 0 2 2h8.2a2 2 0 0 0 2-2V5",
            "M8 13V7.9",
            "M5.7 10.1 8 7.8l2.3 2.3",
        ),
    )
}

val UnlinkOutline16: ImageVector by lazy {
    glyph(
        "UnlinkOutline16",
        listOf(
            "M10 4.4h1.7a3.6 3.6 0 0 1 0 7.2H10",
            "M6 11.6H4.3a3.6 3.6 0 0 1 0-7.2H6",
            "M8 1V3",
            "M8 13v2",
        ),
    )
}

val WalletOutline16: ImageVector by lazy {
    glyph(
        "WalletOutline16",
        listOf(
            "M2.67 3.4H13.32a2 2 0 0 1 2 2V12.6a2 2 0 0 1 -2 2H2.67a2 2 0 0 1 -2 -2V5.4a2 2 0 0 1 2 -2Z",
            "M2.2 3.4 11 0.95a1.1 1.1 0 0 1 1.4 1.05V3.4",
            "M15.3 7.3H11.8a1.7 1.7 0 0 0 0 3.4H15.3",
        ),
    )
}

val WrapOutline16: ImageVector by lazy {
    glyph(
        "WrapOutline16",
        listOf(
            "M0.8 3H15.2",
            "M0.8 8H12a2.6 2.6 0 0 1 0 5.2H8.4",
            "M10.3 11.3 8.4 13.2l1.9 1.9",
            "M0.8 13.2H5",
        ),
    )
}
