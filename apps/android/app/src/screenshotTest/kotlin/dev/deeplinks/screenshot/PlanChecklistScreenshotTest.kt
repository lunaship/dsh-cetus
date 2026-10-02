package dev.deeplinks.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.deeplinks.core.Dsh
import dev.deeplinks.native.DshSpace
import dev.deeplinks.native.MobileTodoItem
import dev.deeplinks.native.PlanChecklist

private val SAMPLE = listOf(
    MobileTodoItem("读方案", "completed"),
    MobileTodoItem("修登录", "in_progress"),
    MobileTodoItem("写测试", "pending"),
)

private val LONG = listOf(
    MobileTodoItem("读方案", "done"),
    MobileTodoItem(
        "把计划清单做成可展开的列表，进行中的那一项高亮，长文本最多两行，其余用省略号收住，不要把会话页顶栏撑开",
        "in_progress",
    ),
)

@PreviewTest
@Preview(name = "plan collapsed light zh", showBackground = true, widthDp = 412, heightDp = 140)
@Composable
internal fun PlanCollapsedLightZh() {
    frame(false, false) { PlanChecklist(SAMPLE, expanded = false) }
}

@PreviewTest
@Preview(name = "plan expanded light zh", showBackground = true, widthDp = 412, heightDp = 240)
@Composable
internal fun PlanExpandedLightZh() {
    frame(false, false) { PlanChecklist(SAMPLE, expanded = true) }
}

@PreviewTest
@Preview(name = "plan expanded dark en", showBackground = true, widthDp = 412, heightDp = 240)
@Composable
internal fun PlanExpandedDarkEn() {
    frame(true, true) {
        PlanChecklist(
            listOf(
                MobileTodoItem("Read the plan", "completed"),
                MobileTodoItem("Fix sign-in", "in_progress"),
                MobileTodoItem("Write tests", "pending"),
            ),
            expanded = true,
        )
    }
}

@PreviewTest
@Preview(name = "plan expanded light zh large", showBackground = true, widthDp = 412, heightDp = 320, fontScale = 1.3f)
@Composable
internal fun PlanExpandedLightZhLarge() {
    frame(false, false) { PlanChecklist(SAMPLE, expanded = true) }
}

@PreviewTest
@Preview(name = "plan expanded dark en large", showBackground = true, widthDp = 412, heightDp = 320, fontScale = 1.3f)
@Composable
internal fun PlanExpandedDarkEnLarge() {
    frame(true, true) {
        PlanChecklist(
            listOf(
                MobileTodoItem("Read the plan", "completed"),
                MobileTodoItem("Fix sign-in", "in_progress"),
                MobileTodoItem("Write tests", "pending"),
            ),
            expanded = true,
        )
    }
}

@PreviewTest
@Preview(name = "plan long zh", showBackground = true, widthDp = 412, heightDp = 280)
@Composable
internal fun PlanLongZh() {
    frame(false, false) { PlanChecklist(LONG, expanded = true) }
}

@PreviewTest
@Preview(name = "plan empty zh", showBackground = true, widthDp = 412, heightDp = 120)
@Composable
internal fun PlanEmptyZh() {
    frame(false, false) { PlanChecklist(emptyList()) }
}

@PreviewTest
@Preview(name = "plan empty dark en", showBackground = true, widthDp = 412, heightDp = 120)
@Composable
internal fun PlanEmptyDarkEn() {
    frame(true, true) { PlanChecklist(emptyList()) }
}

@Composable
private fun frame(dark: Boolean, english: Boolean, content: @Composable () -> Unit) {
    ShotFrame(dark = dark, english = english) {
        Column(Modifier.fillMaxSize().background(Dsh.bgBase).padding(DshSpace.s16)) { content() }
    }
}
