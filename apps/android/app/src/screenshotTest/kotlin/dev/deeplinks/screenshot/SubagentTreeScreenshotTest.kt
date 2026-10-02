package dev.deeplinks.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.core.subagentRunning
import dev.deeplinks.native.DshSpace
import dev.deeplinks.native.SubagentNode
import dev.deeplinks.native.SubagentTree

private val SAMPLE = listOf(
    SubagentNode(
        "a",
        "查日志",
        running = true,
        children = listOf(SubagentNode("a1", "过滤错误", running = false)),
    ),
    SubagentNode("b", "补测试", running = false),
)

@PreviewTest
@Preview(name = "subagent tree light zh", showBackground = true, widthDp = 412, heightDp = 220)
@Composable
internal fun SubagentTreeLightZh() {
    frame(false, false) { SubagentTree(SAMPLE, onSelect = {}) }
}

@PreviewTest
@Preview(name = "subagent tree dark en", showBackground = true, widthDp = 412, heightDp = 220)
@Composable
internal fun SubagentTreeDarkEn() {
    frame(true, true) {
        SubagentTree(
            listOf(
                SubagentNode("a", "Read logs", running = true, children = listOf(SubagentNode("a1", "Filter errors", running = false))),
                SubagentNode("b", "Add tests", running = false),
            ),
            onSelect = {},
        )
    }
}

@PreviewTest
@Preview(name = "subagent tree light zh large", showBackground = true, widthDp = 412, heightDp = 280, fontScale = 1.3f)
@Composable
internal fun SubagentTreeLightZhLarge() {
    frame(false, false) { SubagentTree(SAMPLE, onSelect = {}) }
}

@PreviewTest
@Preview(name = "subagent tree dark en large", showBackground = true, widthDp = 412, heightDp = 280, fontScale = 1.3f)
@Composable
internal fun SubagentTreeDarkEnLarge() {
    frame(true, true) {
        SubagentTree(
            listOf(SubagentNode("a", "Read logs", running = true), SubagentNode("b", "Add tests", running = false)),
            onSelect = {},
        )
    }
}

@PreviewTest
@Preview(name = "subagent tree long zh", showBackground = true, widthDp = 412, heightDp = 200)
@Composable
internal fun SubagentTreeLongZh() {
    frame(false, false) {
        SubagentTree(
            listOf(SubagentNode("a", "把子代理按父会话组成树，长标题最多两行，点进去是现有会话页而且不能再发送", running = true)),
            onSelect = {},
        )
    }
}

@PreviewTest
@Preview(name = "subagent tree empty zh", showBackground = true, widthDp = 412, heightDp = 120)
@Composable
internal fun SubagentTreeEmptyZh() {
    frame(false, false) { SubagentTree(emptyList(), onSelect = {}) }
}

@PreviewTest
@Preview(name = "subagent tree empty dark en", showBackground = true, widthDp = 412, heightDp = 120)
@Composable
internal fun SubagentTreeEmptyDarkEn() {
    frame(true, true) { SubagentTree(emptyList(), onSelect = {}) }
}

@PreviewTest
@Preview(name = "subagent running line zh", showBackground = true, widthDp = 412, heightDp = 80)
@Composable
internal fun SubagentRunningLineZh() {
    frame(false, false) { Text(L.subagentRunning.format(3), color = Dsh.labelPrimary, style = DshType.caption) }
}

@Composable
private fun frame(dark: Boolean, english: Boolean, content: @Composable () -> Unit) {
    ShotFrame(dark = dark, english = english) {
        Column(Modifier.fillMaxSize().background(Dsh.bgBase).padding(DshSpace.s16)) { content() }
    }
}
