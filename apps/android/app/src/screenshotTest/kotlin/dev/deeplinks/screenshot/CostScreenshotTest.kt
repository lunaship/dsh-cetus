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
import dev.deeplinks.core.ModelTier
import dev.deeplinks.core.TierGap
import dev.deeplinks.core.TierPick
import dev.deeplinks.native.HomeBalanceBanner
import dev.deeplinks.native.HomeBalancePreview
import dev.deeplinks.native.ModelTierBar

@PreviewTest
@Preview(name = "balance alert light zh", showBackground = true, widthDp = 412, heightDp = 120)
@Composable
internal fun BalanceAlertLightZh() {
    ShotFrame(dark = false, english = false) {
        Column(Modifier.fillMaxSize().background(Dsh.bgBase).padding(DshSpace.s16)) {
            HomeBalanceBanner(onOpenSettings = {}, preview = HomeBalancePreview("充值余额低于 ¥10.00"))
        }
    }
}

@PreviewTest
@Preview(name = "model tiers light zh", showBackground = true, widthDp = 412, heightDp = 220)
@Composable
internal fun ModelTiersLightZh() {
    ShotFrame(dark = false, english = false) {
        Column(Modifier.fillMaxSize().background(Dsh.bgBase).padding(DshSpace.s16)) {
            ModelTierBar(
                picks = sampleTiers(),
                currentProvider = "deepseek",
                currentModel = "flash",
                currentEffort = "low",
                onPick = { _, _, _ -> },
            )
        }
    }
}

@PreviewTest
@Preview(name = "model tiers dark en", showBackground = true, widthDp = 412, heightDp = 220)
@Composable
internal fun ModelTiersDarkEn() {
    ShotFrame(dark = true, english = true) {
        Column(Modifier.fillMaxSize().background(Dsh.bgBase).padding(DshSpace.s16)) {
            ModelTierBar(
                picks = sampleTiers(),
                currentProvider = "deepseek",
                currentModel = "flash",
                currentEffort = "low",
                onPick = { _, _, _ -> },
            )
        }
    }
}

private fun sampleTiers() = listOf(
    TierPick(ModelTier.Save, "deepseek", "flash", "Flash", "low"),
    TierPick(ModelTier.Balanced, "deepseek", "chat", "Chat", "high"),
    TierPick(ModelTier.Power, gap = TierGap.CustomMissing),
)
