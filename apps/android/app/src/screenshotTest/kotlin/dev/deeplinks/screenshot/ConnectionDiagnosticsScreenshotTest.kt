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
import dev.deeplinks.core.DiagStatus
import dev.deeplinks.core.DiagStep
import dev.deeplinks.core.DiagnosticsReport
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshFontFamily
import dev.deeplinks.core.DshStringsEn
import dev.deeplinks.core.DshStringsZh
import dev.deeplinks.core.HostCheckView
import dev.deeplinks.core.LightDshColors
import dev.deeplinks.core.LocalDshColors
import dev.deeplinks.core.LocalDshFontFamily
import dev.deeplinks.core.LocalDshStrings
import dev.deeplinks.core.LocaleManager
import dev.deeplinks.core.dshTypography
import dev.deeplinks.native.ConnectionDiagnosticsScreen

/** 连接诊断页：全通过、部分失败、全失败，浅色与深色各一套。 */

@PreviewTest
@Preview(name = "diagnostics all pass light zh", showBackground = true, widthDp = 412, heightDp = 1200)
@Composable
private fun DiagnosticsAllPassLightZh() {
    DiagFrame(dark = false, english = false) {
        ConnectionDiagnosticsScreen(allPass(), running = false, onBack = {}, onRun = {}, onCopy = {})
    }
}

@PreviewTest
@Preview(name = "diagnostics all pass dark en", showBackground = true, widthDp = 412, heightDp = 1200)
@Composable
private fun DiagnosticsAllPassDarkEn() {
    DiagFrame(dark = true, english = true) {
        ConnectionDiagnosticsScreen(allPass(), running = false, onBack = {}, onRun = {}, onCopy = {})
    }
}

@PreviewTest
@Preview(name = "diagnostics partial light zh", showBackground = true, widthDp = 412, heightDp = 1200)
@Composable
private fun DiagnosticsPartialLightZh() {
    DiagFrame(dark = false, english = false) {
        ConnectionDiagnosticsScreen(partial(), running = false, onBack = {}, onRun = {}, onCopy = {})
    }
}

@PreviewTest
@Preview(name = "diagnostics partial dark en", showBackground = true, widthDp = 412, heightDp = 1200)
@Composable
private fun DiagnosticsPartialDarkEn() {
    DiagFrame(dark = true, english = true) {
        ConnectionDiagnosticsScreen(partial(), running = false, onBack = {}, onRun = {}, onCopy = {})
    }
}

@PreviewTest
@Preview(name = "diagnostics all fail light zh", showBackground = true, widthDp = 412, heightDp = 1200)
@Composable
private fun DiagnosticsAllFailLightZh() {
    DiagFrame(dark = false, english = false) {
        ConnectionDiagnosticsScreen(allFail(), running = false, onBack = {}, onRun = {}, onCopy = {})
    }
}

@PreviewTest
@Preview(name = "diagnostics all fail dark en", showBackground = true, widthDp = 412, heightDp = 1200)
@Composable
private fun DiagnosticsAllFailDarkEn() {
    DiagFrame(dark = true, english = true) {
        ConnectionDiagnosticsScreen(allFail(), running = false, onBack = {}, onRun = {}, onCopy = {})
    }
}

@Composable
private fun DiagFrame(dark: Boolean, english: Boolean, content: @Composable () -> Unit) {
    LocaleManager.setLanguageForPreview(if (english) "en" else "zh")
    val typography = dshTypography(DshFontFamily)
    MaterialTheme(typography = typography) {
        CompositionLocalProvider(
            LocalDshColors provides if (dark) DarkDshColors else LightDshColors,
            LocalDshStrings provides if (english) DshStringsEn else DshStringsZh,
            LocalDshFontFamily provides DshFontFamily,
            LocalTextStyle provides typography.bodyMedium,
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Dsh.bgBase)) { content() }
        }
    }
}

private fun allPass() = DiagnosticsReport(
    steps = listOf(
        DiagStep("network", DiagStatus.OK, "NET_WIFI", detailKey = "diagNetWifi"),
        DiagStep("lan", DiagStatus.OK, "LAN_OK", elapsedMs = 24),
        DiagStep("remote", DiagStatus.OK, "REMOTE_UP"),
        DiagStep("cert", DiagStatus.OK, "CERT_OK"),
        DiagStep("auth", DiagStatus.OK, "AUTH_OK"),
        DiagStep("clock", DiagStatus.OK, "CLOCK_OK"),
    ),
    hostChecks = listOf(
        HostCheckView("host.rpc", "ok", "HOST_RPC_OK"),
        HostCheckView("host.services", "ok", "HOST_SERVICES_OK"),
        HostCheckView("plugin.version", "ok", "PLUGIN_VERSION_OK"),
        HostCheckView("tls.cert", "ok", "TLS_CERT_OK"),
        HostCheckView("remote.relay", "ok", "REMOTE_READY"),
        HostCheckView("clock", "ok", "CLOCK_OK"),
    ),
)

private fun partial() = DiagnosticsReport(
    steps = listOf(
        DiagStep("network", DiagStatus.WARN, "NET_CELLULAR", detailKey = "diagNetCellular", suggestionKey = "diagSuggestWifi"),
        DiagStep("lan", DiagStatus.FAIL, "LAN_TIMEOUT", suggestionKey = "diagSuggestLan"),
        DiagStep("remote", DiagStatus.OK, "REMOTE_UP"),
        DiagStep("cert", DiagStatus.OK, "CERT_OK"),
        DiagStep("auth", DiagStatus.OK, "AUTH_OK"),
        DiagStep("clock", DiagStatus.WARN, "CLOCK_SKEW", suggestionKey = "diagSuggestClock"),
    ),
    hostChecks = listOf(
        HostCheckView("host.rpc", "ok", "HOST_RPC_OK"),
        HostCheckView("tls.cert", "warn", "TLS_CERT_EXPIRING"),
        HostCheckView("remote.relay", "fail", "REMOTE_DOWN"),
    ),
)

private fun allFail() = DiagnosticsReport(
    steps = listOf(
        DiagStep("network", DiagStatus.FAIL, "NET_NONE", detailKey = "diagNetNone", suggestionKey = "diagSuggestWifi"),
        DiagStep("lan", DiagStatus.FAIL, "LAN_REFUSED", suggestionKey = "diagSuggestLan"),
        DiagStep("remote", DiagStatus.FAIL, "REMOTE_DOWN", suggestionKey = "diagSuggestRemoteDown"),
        DiagStep("cert", DiagStatus.FAIL, "CERT_CHANGED", suggestionKey = "diagSuggestCert"),
        DiagStep("auth", DiagStatus.FAIL, "AUTH_REVOKED", suggestionKey = "diagSuggestAuth"),
        DiagStep("clock", DiagStatus.WARN, "CLOCK_SKEW", suggestionKey = "diagSuggestClock"),
    ),
    hostChecks = emptyList(),
)
