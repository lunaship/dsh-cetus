package dev.deeplinks.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.tools.screenshot.PreviewTest
import androidx.compose.ui.tooling.preview.Preview
import dev.deeplinks.core.Dsh
import dev.deeplinks.native.DshSpace
import dev.deeplinks.native.EstimatedCost
import dev.deeplinks.native.HomeBalanceNotice
import dev.deeplinks.native.HomeBalancePreview
import dev.deeplinks.native.MobileSessionStats
import dev.deeplinks.native.UsagePanel

@PreviewTest
@Preview(name = "balance alert light zh", showBackground = true, widthDp = 412, heightDp = 120)
@Composable
internal fun BalanceAlertLightZh() {
    ShotFrame(dark = false, english = false) {
        Column(Modifier.fillMaxSize().background(Dsh.bgBase).padding(DshSpace.s16)) {
            HomeBalanceNotice(onOpenSettings = {}, preview = HomeBalancePreview("充值余额低于 ¥10.00"))
        }
    }
}

@PreviewTest
@Preview(name = "usage estimate light zh", showBackground = true, widthDp = 412, heightDp = 280)
@Composable
internal fun UsageEstimateLightZh() {
    ShotFrame(dark = false, english = false) {
        Column(Modifier.fillMaxSize().background(Dsh.bgBase).padding(DshSpace.s16)) {
            UsagePanel(
                MobileSessionStats(
                    uncachedInputTokens = 1_200,
                    outputTokens = 340,
                    estimatedCost = EstimatedCost(0.42, "CNY", "2026-10-01", "builtin"),
                ),
            )
        }
    }
}
