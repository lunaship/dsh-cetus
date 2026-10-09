import DLCore
import DLModels
import DLNet
import DLSecurity
import Foundation

/// 一台主机的连接决议（方案 §15.2）。
///
/// 两条路钉扎**同一张**插件证书，所以上层不需要知道自己走的是哪条 ——
/// 只需要在成功后把结果记回选路器（仅直连需要）。
public enum HostConnectionRoute: Equatable, Sendable {
    /// 局域网 / Tailscale，值是实际可用地址。
    case direct(String)
    /// DLP/1 远程。
    case remote
}

/// 建好的主机客户端与其路径。
public struct HostConnection: Sendable {
    public var client: HostClient
    public var route: HostConnectionRoute

    /// 直连地址；远程为 nil（选路器只对直连做 `noteSuccess`）。
    public var directAddress: String? {
        if case .direct(let address) = route { return address }
        return nil
    }

    public var isRemote: Bool { route == .remote }
}

/// 远程传输的**全局**注册表。
///
/// `RemoteTunnelPool` 是 actor，负责「每主机并发流」的额度控制；额度必须全局生效，
/// 因此整个 App 共用一个池，而不是每个调用点各建一个（各建一个会让额度翻倍，
/// 且中继侧可能因此判定 `DEVICE_LIMIT`）。
///
/// 传输实例按主机创建：它的 `routeProvider` / `fingerprintProvider` 需要绑定到
/// 那一台主机的会合参数与证书指纹。
public actor RemoteTransportRegistry {
    public static let shared = RemoteTransportRegistry()

    private let pool: RemoteTunnelPool
    private var transports: [String: LoopbackURLSessionTransport] = [:]
    /// 已知的主机时钟偏移（秒）。`CLOCK_SKEW` 由传输层自动修正一次，
    /// 这里保存下来让后续请求直接用上，避免每次都先撞一次偏差。
    private var clockOffsets: [String: Int] = [:]

    public init(transport: any RemoteTunnelTransport = NWRemoteTunnelTransport()) {
        pool = RemoteTunnelPool(transport: transport)
    }

    /// 记录某主机的时钟偏移（由上层在遇到 `CLOCK_SKEW` 后写入）。
    public func noteClockOffset(_ seconds: Int, hostID: String) {
        clockOffsets[hostID] = seconds
    }

    public func clockOffset(hostID: String) -> Int { clockOffsets[hostID] ?? 0 }

    /// 已缓存传输的主机数。用于诊断与测试「同一主机不会重复建传输」——
    /// 重复建会让每主机并发额度翻倍，中继侧可能因此判定 `DEVICE_LIMIT`。
    public var cachedHostCount: Int { transports.count }

    /// 取（或建）某主机的远程传输。主机没有远程能力时返回 nil。
    public func transport(for host: PairedHost) -> LoopbackURLSessionTransport? {
        guard RemoteRouteBuilder.hasRemoteCapability(host) else { return nil }
        if let existing = transports[host.hostId] { return existing }
        let info = host.remote
        let offset = clockOffsets[host.hostId] ?? 0
        let built = LoopbackURLSessionTransport(
            pool: pool,
            // 本实例只服务这一台主机，所以忽略 host 参数直接返回它的会合参数。
            routeProvider: { _ in try? RemoteRouteBuilder.deviceRoute(info: info, clockOffsetSec: offset) },
            // §15.2 第 2 条：两条路径钉扎同一张插件证书。
            fingerprintProvider: { _ in host.certFingerprint })
        transports[host.hostId] = built
        return built
    }
}

/// 主机可达性探测（直连候选是否真的能握手成功且证书匹配）。
///
/// 从 `InboxLiveService` 抽出来共用：五个调用点原先各写一份同样的闭包。
public enum HostReachability {
    public static func probe(address: String, fingerprint: String) async -> Bool {
        guard let url = URL(string: address) else { return false }
        let delegate = PinnedSessionDelegate(expectedFingerprint: fingerprint)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 1.2
        configuration.timeoutIntervalForResource = 1.2
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 1.2
        let before = delegate.pinFailureCount
        do {
            let (_, response) = try await session.data(for: request)
            session.finishTasksAndInvalidate()
            return response is HTTPURLResponse && delegate.pinFailureCount == before
        } catch {
            session.invalidateAndCancel()
            return false
        }
    }
}

/// 给一台主机建连接：**直连优先，不可达且有远程能力时走远程**（方案 §15.2）。
///
/// 五个服务（首页 / 会话 / 设置 / 账户 / 推送注册）原先各自重复了同一段
/// 「select → guard case .direct → 否则抛离线」的代码，于是**没有一个**会走远程 ——
/// 即便主机已配对远程、手机也能连上 Relay。这里统一成一处，避免再出现
/// 「改了一个忘了另一个」。
public enum HostConnectionFactory {
    /// 建立连接。返回 nil 表示两条路都不可用，调用方据此呈现离线。
    public static func open(
        host: PairedHost,
        token: String,
        routes: RouteSelector,
        registry: RemoteTransportRegistry = .shared
    ) async -> HostConnection? {
        let hasRemote = RemoteRouteBuilder.hasRemoteCapability(host)
        let selection = await routes.select(
            key: host.hostId,
            candidates: RouteSelector.directCandidates(for: host),
            hasRemote: hasRemote
        ) { address in
            await HostReachability.probe(address: address, fingerprint: host.certFingerprint)
        }

        switch selection {
        case .direct(let address):
            guard let base = URL(string: address) else {
                await routes.forget(key: host.hostId)
                return nil
            }
            return HostConnection(
                client: HostClient(
                    baseURL: base, token: token, expectedFingerprint: host.certFingerprint),
                route: .direct(address))

        case .remote:
            guard let transport = await registry.transport(for: host) else {
                await routes.forget(key: host.hostId)
                return nil
            }
            // 远程路径下 `baseURL` 只用于拼路径与 Host 展示，实际连接由传输决定；
            // 取不到局域网地址时用 hostId 兜一个合法 URL。
            let display = Self.remoteBaseURL(host: host)
            return HostConnection(
                client: HostClient(
                    baseURL: display, token: token, expectedFingerprint: host.certFingerprint,
                    transport: transport),
                route: .remote)

        case .noDirectAvailable:
            return nil
        }
    }

    /// 远程路径的展示用根地址。
    ///
    /// 优先沿用主机的首个局域网地址：主机名会出现在 `Host` 头与诊断里，
    /// 用真实主机名比 `hostId` 更便于排查。**不会真的去解析它** ——
    /// 传输会把请求改写到回环桥端点。
    static func remoteBaseURL(host: PairedHost) -> URL {
        if let primary = URL(string: host.primaryUrl), !host.primaryUrl.isEmpty { return primary }
        // `.invalid` 是 RFC 2606 保留后缀，保证不会被误解析到真实主机。
        return URL(string: "https://\(host.hostId).invalid") ?? URL(string: "https://cetus.invalid")!
    }
}
