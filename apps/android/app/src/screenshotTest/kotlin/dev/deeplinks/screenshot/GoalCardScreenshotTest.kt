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
import dev.deeplinks.native.SessionGoal
import dev.deeplinks.native.SessionGoalRef

private const val LONG_GOAL =
    "把手机端的目标、计划和子代理做成和电脑上同一套体验，长文本在状态槽里最多显示三行，多出来的部分用省略号收住，不要把顶栏撑开。"

private val PLAN_ZH = listOf(
    MobileTodoItem("定位事件只推给发起端", "completed"),
    MobileTodoItem("插件广播 approval.resolved", "completed"),
    MobileTodoItem("补审批过期的单测", "in_progress"),
    MobileTodoItem("真机验证两端同步", "pending"),
)

private val PLAN_EN = listOf(
    MobileTodoItem("Find where the event is sent", "completed"),
    MobileTodoItem("Broadcast approval.resolved", "in_progress"),
    MobileTodoItem("Verify on a real phone", "pending"),
)

@PreviewTest
@Preview(name = "goal slot collapsed light zh", showBackground = true, widthDp = 412, heightDp = 120)
@Composable
internal fun GoalCardLightZh() {
    frame(dark = false, english = false) {
        GoalStatusSlot(goal = sampleGoal("把审批状态做成双向同步"), plan = PLAN_ZH)
    }
}

@PreviewTest
@Preview(name = "goal slot expanded dark en", showBackground = true, widthDp = 412, heightDp = 360)
@Composable
internal fun GoalCardDarkEn() {
    frame(dark = true, english = true) {
        GoalStatusSlot(
            goal = sampleGoal("Sync approval state both ways", phase = "paused"),
            plan = PLAN_EN,
            expanded = true,
            onPauseOrResume = {},
            onEdit = {},
            onClear = {},
        )
    }
}

@PreviewTest
@Preview(name = "goal slot expanded light zh", showBackground = true, widthDp = 412, heightDp = 400)
@Composable
internal fun GoalCardExpandedLightZh() {
    frame(dark = false, english = false) {
        GoalStatusSlot(
            goal = sampleGoal("把审批状态做成双向同步"),
            plan = PLAN_ZH,
            expanded = true,
            onPauseOrResume = {},
            onEdit = {},
            onClear = {},
        )
    }
}

@PreviewTest
@Preview(name = "goal slot expanded light zh large", showBackground = true, widthDp = 412, heightDp = 520, fontScale = 1.3f)
@Composable
internal fun GoalCardLightZhLarge() {
    frame(dark = false, english = false) {
        GoalStatusSlot(
            goal = sampleGoal("把审批状态做成双向同步"),
            plan = PLAN_ZH,
            expanded = true,
            onPauseOrResume = {},
            onEdit = {},
            onClear = {},
        )
    }
}

@PreviewTest
@Preview(name = "goal slot collapsed dark en large", showBackground = true, widthDp = 412, heightDp = 140, fontScale = 1.3f)
@Composable
internal fun GoalCardDarkEnLarge() {
    frame(dark = true, english = true) {
        GoalStatusSlot(goal = sampleGoal("Retry failed sign-in", phase = "blocked"), plan = emptyList())
    }
}

@PreviewTest
@Preview(name = "goal slot long zh", showBackground = true, widthDp = 412, heightDp = 240)
@Composable
internal fun GoalCardLongZh() {
    frame(dark = false, english = false) {
        GoalStatusSlot(goal = sampleGoal(LONG_GOAL), plan = emptyList(), expanded = true, onPauseOrResume = {}, onEdit = {}, onClear = {})
    }
}

@PreviewTest
@Preview(name = "goal slot summary zh", showBackground = true, widthDp = 412, heightDp = 120)
@Composable
internal fun GoalCardEmptyZh() {
    frame(dark = false, english = false) {
        GoalStatusSlot(goal = null, summary = "修好登录失败后的重试", plan = emptyList())
    }
}

@Composable
private fun frame(dark: Boolean, english: Boolean, content: @Composable () -> Unit) {
    ShotFrame(dark = dark, english = english) {
        Column(Modifier.fillMaxSize().background(Dsh.bgBase).padding(vertical = DshSpace.s16)) { content() }
    }
}

private fun sampleGoal(objective: String, phase: String = "active") = SessionGoal(
    ref = SessionGoalRef("g1", 2),
    objective = objective,
    phase = phase,
    maxGoalRounds = 8,
    roundsStarted = 3,
)
