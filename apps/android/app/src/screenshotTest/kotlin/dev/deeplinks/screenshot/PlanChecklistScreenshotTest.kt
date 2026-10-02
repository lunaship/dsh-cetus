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
import dev.deeplinks.native.GoalStatusSlot
import dev.deeplinks.native.MobileTodoItem

private val SAMPLE = listOf(
    MobileTodoItem("读方案", "completed"),
    MobileTodoItem("修登录", "in_progress"),
    MobileTodoItem("写测试", "pending"),
)

private val LONG = listOf(
    MobileTodoItem("读方案", "done"),
    MobileTodoItem(
        "把计划清单做成可展开的列表，进行中的那一项加粗，长文本最多两行，其余用省略号收住，不要把会话页顶栏撑开",
        "in_progress",
    ),
)

/** 只有计划、没有目标时，状态槽显示「计划 1/3」。 */
@PreviewTest
@Preview(name = "plan collapsed light zh", showBackground = true, widthDp = 412, heightDp = 140)
@Composable
internal fun PlanCollapsedLightZh() {
    frame(false, false) { GoalStatusSlot(goal = null, plan = SAMPLE) }
}

@PreviewTest
@Preview(name = "plan expanded light zh", showBackground = true, widthDp = 412, heightDp = 240)
@Composable
internal fun PlanExpandedLightZh() {
    frame(false, false) { GoalStatusSlot(goal = null, plan = SAMPLE, expanded = true) }
}

@PreviewTest
@Preview(name = "plan expanded dark en", showBackground = true, widthDp = 412, heightDp = 240)
@Composable
internal fun PlanExpandedDarkEn() {
    frame(true, true) {
        GoalStatusSlot(
            goal = null,
            plan = listOf(
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
    frame(false, false) { GoalStatusSlot(goal = null, plan = LONG, expanded = true) }
}

@Composable
private fun frame(dark: Boolean, english: Boolean, content: @Composable () -> Unit) {
    ShotFrame(dark = dark, english = english) {
        Column(Modifier.fillMaxSize().background(Dsh.bgBase).padding(vertical = DshSpace.s16)) { content() }
    }
}
