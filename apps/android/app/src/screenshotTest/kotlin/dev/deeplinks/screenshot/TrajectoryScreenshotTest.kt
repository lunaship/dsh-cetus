package dev.deeplinks.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.deeplinks.core.Dsh
import dev.deeplinks.native.MobileMessage
import dev.deeplinks.native.TrajectoryView

private val TRACE = listOf(
    MobileMessage(id = "u1", role = "user", text = "审批状态在手机和电脑之间不同步"),
    MobileMessage(id = "t1", role = "tool_call", text = "", toolName = "grep", toolArgs = """{"pattern":"approval.resolved"}"""),
    MobileMessage(id = "r1", role = "tool_result", text = "4 处匹配\nsrc/host-events.js:12"),
    MobileMessage(id = "t2", role = "tool_call", text = "", toolName = "edit_file", toolArgs = """{"file_path":"src/host-events.js"}"""),
    MobileMessage(id = "r2", role = "tool_result", text = "已写入"),
    MobileMessage(id = "t3", role = "tool_call", text = "", toolName = "bash", toolArgs = """{"command":"npm test -- host-events"}"""),
    MobileMessage(id = "r3", role = "tool_result", text = "expected 2 subscribers", outcome = "error"),
    MobileMessage(id = "u2", role = "user", text = "继续，把过期也补上"),
    MobileMessage(id = "th", role = "reasoning", text = "补审批过期的单测", durationMs = 6_000),
    MobileMessage(id = "t4", role = "tool_call", text = "", toolName = "edit_file", toolArgs = """{"file_path":"test/host-events.test.mjs"}"""),
    MobileMessage(id = "r4", role = "tool_result", text = "已写入"),
    MobileMessage(id = "t5", role = "tool_call", text = "", toolName = "bash", toolArgs = """{"command":"go test ./..."}"""),
)

/** v4 4.7：轨迹二级页（搜索 + 筛选 chip + 按轮分组，新的在上）。 */
@PreviewTest
@Preview(name = "trajectory light zh", showBackground = true, widthDp = 412, heightDp = 760)
@Composable
internal fun TrajectoryLightZh() {
    ShotFrame(dark = false, english = false) {
        TrajectoryView(TRACE, running = false, elapsedSec = 0, modifier = Modifier.fillMaxSize().background(Dsh.bgBase))
    }
}

@PreviewTest
@Preview(name = "trajectory running dark en", showBackground = true, widthDp = 412, heightDp = 760)
@Composable
internal fun TrajectoryRunningDarkEn() {
    ShotFrame(dark = true, english = true) {
        TrajectoryView(TRACE, running = true, elapsedSec = 42, modifier = Modifier.fillMaxSize().background(Dsh.bgBase))
    }
}
