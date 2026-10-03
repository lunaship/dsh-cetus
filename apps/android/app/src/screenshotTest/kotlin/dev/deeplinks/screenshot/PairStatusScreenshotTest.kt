package dev.deeplinks.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshS
import dev.deeplinks.core.sharePickTitle
import dev.deeplinks.devices.PairFailedScreen
import dev.deeplinks.devices.PairWaitingScreen
import dev.deeplinks.native.MobileSession
import dev.deeplinks.native.SharePickerContent
import dev.deeplinks.native.ui.v4.DlBottomSheetSurface

/** 1.5 等电脑批准 / 1.6 配对失败 / 8.3 分享选会话（v4 R5）。 */

private const val NOW = 1_760_000_000_000L

@PreviewTest
@Preview(name = "pair waiting light zh", showBackground = true, widthDp = 412, heightDp = 760)
@Composable
internal fun PairWaitingLightZh() = ShotFrame(dark = false, english = false) {
    PairWaitingScreen(computerName = "Helios 的 MacBook Pro", deviceName = "Pixel 9 Pro", viaRemote = false, onCancel = {})
}

@PreviewTest
@Preview(name = "pair waiting dark en", showBackground = true, widthDp = 412, heightDp = 760)
@Composable
internal fun PairWaitingDarkEn() = ShotFrame(dark = true, english = true) {
    PairWaitingScreen(computerName = "Helios's MacBook Pro", deviceName = "Pixel 9 Pro", viaRemote = true, onCancel = {})
}

@PreviewTest
@Preview(name = "pair failed network light zh", showBackground = true, widthDp = 412, heightDp = 760)
@Composable
internal fun PairFailedNetworkLightZh() = ShotFrame(dark = false, english = false) {
    PairFailedScreen(message = "", network = true, onRescan = {}, onBack = {})
}

@PreviewTest
@Preview(name = "pair failed dark en", showBackground = true, widthDp = 412, heightDp = 760)
@Composable
internal fun PairFailedDarkEn() = ShotFrame(dark = true, english = true) {
    PairFailedScreen(message = "This pairing code has expired. Refresh it on the computer and scan again.", network = false, onRescan = {}, onBack = {})
}

private fun shareSessions(en: Boolean) = listOf(
    MobileSession("a", if (en) "Polish approval state sync" else "完善审批状态同步", NOW - 60_000, running = true, blank = false, cwd = "/Users/helios/dsh-links", agentPreset = null),
    MobileSession("b", if (en) "Fix relay reconnect" else "修复中继重连", NOW - 3 * 3_600_000, running = false, blank = false, cwd = "/Users/helios/relay", agentPreset = null),
    MobileSession("c", "", NOW - 26 * 3_600_000, running = false, blank = false, cwd = null, agentPreset = null),
    MobileSession("d", "hidden", NOW, running = true, blank = false, cwd = null, agentPreset = null, origin = "subagent"),
)

@Composable
private fun ShareWall(en: Boolean) {
    Box(Modifier.fillMaxSize().background(Dsh.bgBase), contentAlignment = Alignment.BottomCenter) {
        DlBottomSheetSurface(
            modifier = Modifier.fillMaxWidth(),
            title = DshS.sharePickTitle,
            subtitle = if (en) "2 images · Crash log from staging" else "2 张图片 · 测试环境的崩溃日志",
        ) {
            SharePickerContent(shareSessions(en), onPick = {}, now = NOW)
        }
    }
}

@PreviewTest
@Preview(name = "share picker light zh", showBackground = true, widthDp = 412, heightDp = 760)
@Composable
internal fun SharePickerLightZh() = ShotFrame(dark = false, english = false) { ShareWall(en = false) }

@PreviewTest
@Preview(name = "share picker dark en", showBackground = true, widthDp = 412, heightDp = 760)
@Composable
internal fun SharePickerDarkEn() = ShotFrame(dark = true, english = true) { ShareWall(en = true) }
