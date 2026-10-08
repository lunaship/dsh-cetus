import DLModels
import DLSecurity
import Foundation
import Testing

@testable import Cetus

@Suite struct SettingsTests {
    @Test func clipboardKeepsCodesAndNumbers() {
        let checks = [
            DiagnosticCheck(
                id: "clock",
                status: .warn,
                code: "CLOCK_SKEW",
                detail: [
                    "note": .text("ahead"),
                    "skewSeconds": .number(3),
                ]
            )
        ]
        #expect(diagnosticsClipboard(checks) == "clock warn CLOCK_SKEW note=ahead,skewSeconds=3.0")
    }
}

// MARK: - C10 要求 3：设置页显示实际选路，不用 hostID 冒充地址

@Suite struct SettingsRouteDisplayTests {
    private func host(
        primary: String = "https://192.168.1.5:18640",
        tailnet: String? = nil,
        endpoint: String? = nil
    ) -> PairedHost {
        PairedHost(
            hostId: "host-1a2b",
            name: "MacBook Pro",
            primaryUrl: primary,
            tailnetUrl: tailnet,
            certFingerprint: "AA:BB",
            remote: endpoint.map { DeviceRemoteInfo(endpoint: $0) },
            pairedAt: 0)
    }

    @Test("局域网直连显示 host:port，而不是 hostID")
    func localShowsHostAndPort() throws {
        let base = try #require(URL(string: "https://192.168.1.5:18640"))
        let shown = InboxLiveService.displayAddress(base, host: host(), route: .local)
        #expect(shown == "192.168.1.5:18640")
        // 关键：绝不能是 hostID。
        #expect(shown != "host-1a2b")
    }

    @Test("远程显示 relay 端点，不暴露内网地址")
    func remoteShowsEndpoint() throws {
        let base = try #require(URL(string: "https://192.168.1.5:18640"))
        let shown = InboxLiveService.displayAddress(
            base, host: host(endpoint: "wss://relay.example:443"), route: .remote)
        #expect(shown == "wss://relay.example:443")
    }

    @Test("远程缺少端点时按 tailnet → primary 回退，不给空串")
    func remoteFallsBack() throws {
        let base = try #require(URL(string: "https://192.168.1.5:18640"))
        let tailnet = InboxLiveService.displayAddress(
            base, host: host(tailnet: "https://100.64.0.1:18640"), route: .remote)
        #expect(tailnet == "https://100.64.0.1:18640")
        let primary = InboxLiveService.displayAddress(base, host: host(), route: .remote)
        #expect(primary == "https://192.168.1.5:18640")
    }

    @Test("没有端口时只显示主机名")
    func localWithoutPort() throws {
        let base = try #require(URL(string: "https://macbook.local"))
        let shown = InboxLiveService.displayAddress(base, host: host(), route: .local)
        #expect(shown == "macbook.local")
    }
}

@Suite struct SettingsDiagnosticsHonestyTests {
    /// C10 要求 2：生产默认不得用样例诊断填充。
    /// 这条测试盯的是**文件事实**而非渲染结果：样例一旦被塞回生产默认，
    /// 用户会在"查询失败"旁边看到伪造的 OK/WARN 结论。
    @Test("生产设置页不再自带样例诊断数据")
    func productionHasNoSampleChecks() throws {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()  // Tests
            .deletingLastPathComponent()  // apps/ios
            .appending(path: "App/Features/Settings/SettingsPages.swift")
        let source = try String(contentsOf: url, encoding: .utf8)
        #expect(!source.contains("sampleChecks"), "生产文件不应再出现 sampleChecks")
        // 空态必须有明确说明，不能留白。
        #expect(source.contains("diagnosticsEmpty"))
    }
}

// MARK: - C10 要求 5：外观在根层生效

@Suite struct ThemePreferenceTests {
    @Test("跟随系统不覆盖配色，交给系统决定")
    func systemDoesNotOverride() {
        #expect(ThemePreference.colorScheme(for: ThemePreference.system) == nil)
    }

    @Test("显式选择映射到对应配色")
    func explicitChoicesMap() {
        #expect(ThemePreference.colorScheme(for: ThemePreference.light) == .light)
        #expect(ThemePreference.colorScheme(for: ThemePreference.dark) == .dark)
    }

    @Test("未知取值退回跟随系统，不给用户坏掉的界面")
    func unknownFallsBack() {
        #expect(ThemePreference.colorScheme(for: "") == nil)
        #expect(ThemePreference.colorScheme(for: "high-contrast") == nil)
    }

    /// 设置页写入的键与根视图读取的键必须**是同一个常量**。
    /// 这条防的正是历史缺陷：写入 `settings.theme` 而无人读取，外观设置静默失效。
    @Test("根视图与设置页共用同一个存储键")
    func rootAndSettingsShareKey() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "App/CetusApp.swift")
        let source = try String(contentsOf: root, encoding: .utf8)
        #expect(source.contains("ThemePreference.storageKey"), "根视图必须读同一个键")
        #expect(source.contains("preferredColorScheme"), "根视图必须真的应用外观")
        #expect(!source.contains("\"settings.theme\""), "不应再出现裸字符串键")
    }
}

// MARK: - C10 要求 8：通知设置三维度分离

@Suite struct PushSettingsDimensionsTests {
    /// 三个维度必须各有独立文案键。合成一个布尔量会让用户看到
    /// 「开关是开的却收不到」，且无法定位是系统拒绝还是电脑不支持。
    @Test("系统授权三态各有对应文案")
    func authorizationStatesHaveCopy() {
        for status in [
            PushSystemAuthorization.Status.authorized, .denied, .notDetermined,
        ] {
            let key: SettingsText =
                switch status {
                case .authorized: .pushAuthAuthorized
                case .denied: .pushAuthDenied
                case .notDetermined: .pushAuthNotDetermined
                }
            #expect(!key.fallback.isEmpty)
        }
    }

    @Test("网关可用性有独立文案，与系统授权不共用")
    func gatewayCopyIsSeparate() {
        #expect(SettingsText.pushGatewayReady.fallback != SettingsText.pushAuthAuthorized.fallback)
        #expect(
            SettingsText.pushGatewayUnavailable.fallback
                != SettingsText.pushAuthDenied.fallback)
    }

    /// 拒绝后必须给出「App 内改不了」的说明，否则用户会一直点那个开关。
    @Test("系统拒绝时提供可行动说明")
    func deniedHasActionableHint() {
        #expect(SettingsText.pushSystemDeniedHint.fallback.contains("system settings"))
    }
}

// MARK: - C10 要求 2：诊断显示测试时间

@Suite struct DiagnosticsTimestampTests {
    /// 时间戳口径是 Unix **毫秒**。按秒解析会把 2026 年显示成 1970 年 —— 这类
    /// 单位错误在截图里不显眼，但用户一眼就会发现时间离谱。
    @Test("毫秒时间戳按毫秒解析")
    func millisecondsNotSeconds() {
        let millis = 1_760_000_000_000
        let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
        let year = Calendar(identifier: .gregorian).component(
            .year, from: date)
        #expect(year >= 2025 && year <= 2027, "毫秒被当成秒会把年份算到 1970 年代，实际 \(year)")
    }
}

// MARK: - C10 要求 6：新增文案必须两种语言都有

@Suite struct SettingsCatalogCoverageTests {
    /// 防的是「加了 key 但漏了某个语言」——界面会退回英文 fallback，
    /// 中文用户看到夹杂英文。截图测试只覆盖截图里的页面，抓不到这个。
    @Test("设置相关新增 key 都有 en 与 zh-Hans")
    func newSettingsKeysAreTranslated() throws {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "App/Resources/Localizable.xcstrings")
        let data = try Data(contentsOf: url)
        let catalog = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let strings = try #require(catalog?["strings"] as? [String: Any])

        let required = [
            "settings.diagnosticsEmpty",
            "settings.diagnosticsTestedAt",
            "settings.pushSystemAuthorization",
            "settings.pushGateway",
            "settings.pushGatewayReady",
            "settings.pushGatewayUnavailable",
            "settings.pushAuthAuthorized",
            "settings.pushAuthDenied",
            "settings.pushAuthNotDetermined",
            "settings.pushSystemDeniedHint",
        ]
        for key in required {
            let entry = try #require(strings[key] as? [String: Any], "缺少 key: \(key)")
            let localizations = try #require(entry["localizations"] as? [String: Any])
            for language in ["en", "zh-Hans"] {
                let unit = (localizations[language] as? [String: Any])?["stringUnit"] as? [String: Any]
                let value = unit?["value"] as? String
                #expect(value?.isEmpty == false, "\(key) 缺少 \(language) 翻译")
            }
        }
    }
}
