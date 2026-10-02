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
import dev.deeplinks.native.GoalCard
import dev.deeplinks.native.SessionGoal
import dev.deeplinks.native.SessionGoalRef

private const val LONG_GOAL =
    "把手机端的目标、计划和子代理做成和电脑上同一套体验，长文本在卡片里最多显示两行，多出来的部分用省略号收住，不要把顶栏撑开。"

@PreviewTest
@Preview(name = "goal card light zh", showBackground = true, widthDp = 412, heightDp = 180)
@Composable
internal fun GoalCardLightZh() {
    frame(dark = false, english = false) {
        GoalCard(goal = sampleGoal("把登录失败改成可重试"), onToggle = {}, onPauseOrResume = {}, onEdit = {}, onClear = {})
    }
}

@PreviewTest
@Preview(name = "goal card dark en", showBackground = true, widthDp = 412, heightDp = 180)
@Composable
internal fun GoalCardDarkEn() {
    frame(dark = true, english = true) {
        GoalCard(
            goal = sampleGoal("Retry failed sign-in", phase = "paused"),
            onToggle = {},
            onPauseOrResume = {},
            onEdit = {},
            onClear = {},
        )
    }
}

@PreviewTest
@Preview(name = "goal card light zh large", showBackground = true, widthDp = 412, heightDp = 240, fontScale = 1.3f)
@Composable
internal fun GoalCardLightZhLarge() {
    frame(dark = false, english = false) {
        GoalCard(goal = sampleGoal("把登录失败改成可重试"), onToggle = {}, onPauseOrResume = {}, onEdit = {}, onClear = {})
    }
}

@PreviewTest
@Preview(name = "goal card dark en large", showBackground = true, widthDp = 412, heightDp = 240, fontScale = 1.3f)
@Composable
internal fun GoalCardDarkEnLarge() {
    frame(dark = true, english = true) {
        GoalCard(
            goal = sampleGoal("Retry failed sign-in", phase = "blocked"),
            onToggle = {},
            onPauseOrResume = {},
            onEdit = {},
            onClear = {},
        )
    }
}

@PreviewTest
@Preview(name = "goal card long zh", showBackground = true, widthDp = 412, heightDp = 200)
@Composable
internal fun GoalCardLongZh() {
    frame(dark = false, english = false) {
        GoalCard(goal = sampleGoal(LONG_GOAL), onToggle = {}, onPauseOrResume = {}, onEdit = {}, onClear = {})
    }
}

@PreviewTest
@Preview(name = "goal card empty zh", showBackground = true, widthDp = 412, heightDp = 120)
@Composable
internal fun GoalCardEmptyZh() {
    frame(dark = false, english = false) { GoalCard(goal = null) }
}

@PreviewTest
@Preview(name = "goal card empty dark en", showBackground = true, widthDp = 412, heightDp = 120)
@Composable
internal fun GoalCardEmptyDarkEn() {
    frame(dark = true, english = true) { GoalCard(goal = null) }
}

@Composable
private fun frame(dark: Boolean, english: Boolean, content: @Composable () -> Unit) {
    ShotFrame(dark = dark, english = english) {
        Column(Modifier.fillMaxSize().background(Dsh.bgBase).padding(DshSpace.s16)) { content() }
    }
}

private fun sampleGoal(objective: String, phase: String = "active") = SessionGoal(
    ref = SessionGoalRef("g1", 2),
    objective = objective,
    phase = phase,
)
