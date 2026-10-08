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
