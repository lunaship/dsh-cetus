import DLCore
import DLModels
import DLNet
import DLSecurity
import Foundation
import Testing

@testable import Cetus

/// C11 §15.2：**远程生产选路**。
///
/// 接线前五个调用点都写死 `guard case .direct`，于是局域网与 Tailscale 都不可达时
/// **一律抛离线** —— 即便主机已配对远程、手机也能连上 Relay。
/// 这一组测试守住「远程真的会被选中」，而不只是「协议层能跑」。
@MainActor
@Suite("远程生产选路 (C11 §15.2)")
struct HostConnectionFactoryTests {
    private static func b64(_ bytes: Int, seed: UInt8) -> String {
        var data = Data(count: bytes)
        for i in 0..<bytes { data[i] = seed &+ UInt8(i % 251) }
        return data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func remoteInfo() -> DeviceRemoteInfo {
        DeviceRemoteInfo(
            endpoint: "wss://relay.example/ws",
            routeId: b64(16, seed: 7),
            deviceHandle: b64(16, seed: 19),
            relayKey: b64(32, seed: 31))
    }

    /// 一个**不可达**的局域网地址：探测必然失败，用来逼出远程分支。
    private static func unreachableHost(remote: DeviceRemoteInfo?) -> PairedHost {
        PairedHost(
            hostId: "host-1",
            name: "电脑",
            // 192.0.2.0/24 是 RFC 5737 的文档保留段，保证不可达。
            primaryUrl: "https://192.0.2.10:18640",
            certFingerprint: String(repeating: "ab", count: 32),
            remote: remote,
            pairedAt: 0)
    }

    /// **核心断言**：直连不可达 + 有远程能力 → 走远程，而不是报离线。
    @Test func fallsBackToRemoteWhenDirectIsUnreachable() async throws {
        let host = Self.unreachableHost(remote: Self.remoteInfo())
        let routes = RouteSelector()
        // 注入一个假隧道运输，保证不会真的连中继。
        let registry = RemoteTransportRegistry(transport: UnusedTunnelTransport())

        let connection = await HostConnectionFactory.open(
            host: host, token: "token", routes: routes, registry: registry)

        let resolved = try #require(connection, "有远程能力时不该返回 nil（那等于永远离线）")
        #expect(resolved.isRemote, "直连全部不可达时应落到远程")
        #expect(resolved.directAddress == nil, "远程路径不该记直连地址")
    }

    /// 没有远程能力时必须老实报离线 —— 不能伪造一个远程连接。
    @Test func noRemoteCapabilityStaysOffline() async {
        let host = Self.unreachableHost(remote: nil)
        let routes = RouteSelector()
        let registry = RemoteTransportRegistry(transport: UnusedTunnelTransport())

        let connection = await HostConnectionFactory.open(
            host: host, token: "token", routes: routes, registry: registry)
        #expect(connection == nil, "没有远程能力时只能离线")
    }

    /// 远程能力信息不完整（缺 relayKey 等）同样不算「有远程能力」。
    @Test func incompleteRemoteInfoStaysOffline() async {
        let host = Self.unreachableHost(
            remote: DeviceRemoteInfo(
                endpoint: "wss://relay.example/ws", routeId: Self.b64(16, seed: 1),
                deviceHandle: nil, relayKey: nil))
        let routes = RouteSelector()
        let registry = RemoteTransportRegistry(transport: UnusedTunnelTransport())

        let connection = await HostConnectionFactory.open(
            host: host, token: "token", routes: routes, registry: registry)
        #expect(connection == nil, "远程凭据不全时不应尝试远程（会在握手期失败，不如直接离线）")
    }

    /// 远程路径的 `baseURL` 只用于拼路径与 Host 展示，必须是合法 URL。
    @Test func remoteBaseURLIsUsableAndPrefersPrimaryAddress() throws {
        let withPrimary = Self.unreachableHost(remote: Self.remoteInfo())
        let url = HostConnectionFactory.remoteBaseURL(host: withPrimary)
        #expect(url.host == "192.0.2.10", "优先沿用主机地址，便于诊断里看出是哪台电脑")

        // 没有可用主地址时退到保留后缀，绝不指向真实主机。
        let noPrimary = PairedHost(
            hostId: "h2", name: "n", primaryUrl: "", certFingerprint: "x", remote: Self.remoteInfo(),
            pairedAt: 0)
        let fallback = HostConnectionFactory.remoteBaseURL(host: noPrimary)
        #expect(fallback.host?.hasSuffix(".invalid") == true, "兜底域名必须用保留后缀 .invalid")
    }

    /// 远程传输按主机缓存：同一主机两次取到**同一个**实例。
    ///
    /// 每次新建会让每主机并发额度翻倍，中继侧可能因此判定 `DEVICE_LIMIT`。
    @Test func transportIsCachedPerHost() async {
        let registry = RemoteTransportRegistry(transport: UnusedTunnelTransport())
        let host = Self.unreachableHost(remote: Self.remoteInfo())
        let first = await registry.transport(for: host)
        let second = await registry.transport(for: host)
        #expect(first != nil)
        #expect(second != nil)
        #expect(await registry.cachedHostCount == 1, "同一主机必须复用，不能每次新建（额度会翻倍）")
    }

    /// 时钟偏移按主机记录，供后续请求复用。
    @Test func clockOffsetIsRememberedPerHost() async {
        let registry = RemoteTransportRegistry(transport: UnusedTunnelTransport())
        #expect(await registry.clockOffset(hostID: "h") == 0)
        await registry.noteClockOffset(90, hostID: "h")
        #expect(await registry.clockOffset(hostID: "h") == 90)
    }
}

/// 只用于构造 `RemoteTunnelPool` 的占位运输：本组测试不建立真实隧道。
private struct UnusedTunnelTransport: RemoteTunnelTransport {
    func open(_ route: RemoteTunnelRoute) async throws -> any RemoteTunnel {
        throw RemoteTunnelError.relayUnreachable
    }
}
