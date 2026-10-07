import DLRemote
import Foundation
import os

/// 流类型（RFC §5.8 把短请求与 SSE 分开计数）。
public enum RemoteStreamKind: Sendable, Equatable {
    case short
    case sse
}

/// 一条隧道的一次租用。调用方用完必须 `release()`，否则占用不会归还。
public protocol RemoteTunnelLease: Sendable {
    var tunnel: any RemoteTunnel { get }
    func release() async
}

/// 隧道来源与限额（RFC §5.8）。
public protocol RemoteTunnelPooling: Sendable {
    /// 为 `host` 取一条可用隧道。超出限额时抛错，不排队等无限久。
    ///
    /// - Parameters:
    ///   - host: SSE 计数与 `Host` 语义用的稳定标识。
    ///   - kind: 短请求 / SSE，分开计数（RFC §5.8）。
    ///   - route: 会合参数；隧道没建成时不得留下占用。
    func acquire(host: String, kind: RemoteStreamKind, route: RemoteTunnelRoute) async throws
        -> any RemoteTunnelLease
}

/// RFC §5.8 的连接池参数。
public struct RemoteTunnelPoolLimits: Sendable, Equatable {
    /// 空闲连接上限（保留 50 秒）。
    public var maxIdle: Int
    /// 空闲保留时长（秒）。
    public var idleRetention: TimeInterval
    /// 短请求在途上限。
    public var maxInFlightShort: Int
    /// 每主机 SSE 上限。
    public var maxSSEPerHost: Int
    /// 每设备（跨主机）总上限。
    public var maxPerDevice: Int

    public init(
        maxIdle: Int = 3,
        idleRetention: TimeInterval = 50,
        maxInFlightShort: Int = 4,
        maxSSEPerHost: Int = 2,
        maxPerDevice: Int = 12
    ) {
        self.maxIdle = maxIdle
        self.idleRetention = idleRetention
        self.maxInFlightShort = maxInFlightShort
        self.maxSSEPerHost = maxSSEPerHost
        self.maxPerDevice = maxPerDevice
    }

    /// RFC §5.8 默认值。
    public static let standard = RemoteTunnelPoolLimits()
}

/// 按 RFC §5.8 计数的隧道池。
///
/// 本 PR 的取向是「先保证不超发，再谈复用」：
/// - **短请求**：每条请求开一条新隧道，用完即关。计数受 `maxInFlightShort` 约束。
///   不在这里做空闲复用——空闲复用的收益要等有真实中继联调再评估（明确列为未完成项）。
/// - **SSE**：占用一条长隧道，按 `maxSSEPerHost` 计。
/// - 总额受 `maxPerDevice` 约束（`busy(code: DEVICE_LIMIT)`）。
public actor RemoteTunnelPool: RemoteTunnelPooling {
    private let transport: any RemoteTunnelTransport
    private let limits: RemoteTunnelPoolLimits

    private var inFlightShort = 0
    private var ssePerHost: [String: Int] = [:]

    public init(transport: any RemoteTunnelTransport, limits: RemoteTunnelPoolLimits = .standard) {
        self.transport = transport
        self.limits = limits
    }

    /// 当前在途短请求数（测试用）。
    public var currentInFlightShort: Int { inFlightShort }
    /// 某主机的 SSE 占用数（测试用）。
    public func currentSSE(forHost host: String) -> Int { ssePerHost[host] ?? 0 }
    /// 当前总占用（测试用）。
    public var currentTotal: Int { inFlightShort + ssePerHost.values.reduce(0, +) }

    /// - Parameters:
    ///   - host: 用于 SSE 计数与 `Host` 语义的稳定标识（本 PR 为请求的 host）。
    ///   - kind: `short` 计入短请求额度，`sse` 计入每主机 SSE 额度。
    ///   - route: 会合参数。隧道建立失败时立刻归还额度，不留悬挂计数。
    public func acquire(host: String, kind: RemoteStreamKind, route: RemoteTunnelRoute) async throws
        -> any RemoteTunnelLease
    {
        try checkLimits(host: host, kind: kind)
        reserve(host: host, kind: kind)
        do {
            let tunnel = try await transport.open(route)
            return Lease(tunnel: tunnel, pool: self, host: host, kind: kind)
        } catch {
            release(host: host, kind: kind)
            throw error
        }
    }

    private func checkLimits(host: String, kind: RemoteStreamKind) throws {
        // 每设备总额：12（RFC §5.8）。
        if currentTotal >= limits.maxPerDevice {
            throw RemoteTunnelError.busy(code: DlpWire.Reject.deviceLimit)
        }
        switch kind {
        case .short:
            if inFlightShort >= limits.maxInFlightShort {
                throw RemoteTunnelError.busy(code: DlpWire.Reject.deviceLimit)
            }
        case .sse:
            if (ssePerHost[host] ?? 0) >= limits.maxSSEPerHost {
                throw RemoteTunnelError.busy(code: DlpWire.Reject.deviceLimit)
            }
        }
    }

    private func reserve(host: String, kind: RemoteStreamKind) {
        switch kind {
        case .short: inFlightShort += 1
        case .sse: ssePerHost[host, default: 0] += 1
        }
    }

    fileprivate func release(host: String, kind: RemoteStreamKind) {
        switch kind {
        case .short: inFlightShort = max(0, inFlightShort - 1)
        case .sse: ssePerHost[host] = max(0, (ssePerHost[host] ?? 0) - 1)
        }
    }

    /// 租约：`release()` 关闭隧道并归还额度。
    private final class Lease: RemoteTunnelLease, @unchecked Sendable {
        let tunnel: any RemoteTunnel
        private let pool: RemoteTunnelPool
        private let host: String
        private let kind: RemoteStreamKind
        private let released = OSAllocatedUnfairLock<Bool>(initialState: false)

        init(tunnel: any RemoteTunnel, pool: RemoteTunnelPool, host: String, kind: RemoteStreamKind) {
            self.tunnel = tunnel
            self.pool = pool
            self.host = host
            self.kind = kind
        }

        func release() async {
            let shouldRelease = released.withLock { current -> Bool in
                if current { return false }
                current = true
                return true
            }
            guard shouldRelease else { return }
            await tunnel.close()
            await pool.release(host: host, kind: kind)
        }
    }
}
