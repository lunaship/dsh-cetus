package dev.deeplinks.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.deeplinks.core.DarkDshColors
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshStringsEn
import dev.deeplinks.core.DshStringsZh
import dev.deeplinks.core.LightDshColors
import dev.deeplinks.core.LocalDshColors
import dev.deeplinks.core.LocalDshStrings
import dev.deeplinks.core.LocaleManager
import dev.deeplinks.core.dshTypography
import dev.deeplinks.devices.EmptyDevicesState

/** 1.2 欢迎 / 未配对。 */

@Composable
private fun PairingFrame(dark: Boolean, english: Boolean, content: @Composable () -> Unit) {
    LocaleManager.setLanguageForPreview(if (english) "en" else "zh")
    val typography = dshTypography()
    MaterialTheme(typography = typography) {
        CompositionLocalProvider(
            LocalDshColors provides if (dark) DarkDshColors else LightDshColors,
            LocalDshStrings provides if (english) DshStringsEn else DshStringsZh,
            LocalTextStyle provides typography.bodyMedium,
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Dsh.bgBase)) { content() }
        }
    }
}

@PreviewTest
@Preview(name = "welcome light zh", showBackground = true, widthDp = 412, heightDp = 760)
@Composable
internal fun WelcomeLightZh() {
    PairingFrame(dark = false, english = false) { EmptyDevicesState(onScan = {}, onAlbum = {}) }
}

@PreviewTest
@Preview(name = "welcome dark en", showBackground = true, widthDp = 412, heightDp = 760)
@Composable
internal fun WelcomeDarkEn() {
    PairingFrame(dark = true, english = true) { EmptyDevicesState(onScan = {}, onAlbum = {}) }
}
