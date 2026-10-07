import DLModels
import DLSecurity
import Foundation

/// 选路结果（RFC 0001 §7.2）：先直连（主地址 → Tailscale），都探测不通且这台电脑有远程能力时才回落到中继。
///
/// 语义边界：
/// - `.relay(RemoteTarget)` **只表示「该走中继」这个决定**，不表示中继已经连通，也不表示已经取得任何凭据。
///   本 actor 不建连接、不发帧、不碰 token / 会合密钥，真正的传输由上层（DLNet/Remote）按 target 建立。
/// - 没有远程能力（`PairedHost.remote == nil`）时不允许返回 `.relay`，回落失败仍是 `.noDirectAvailable`。
public enum RouteSelection: Equatable, Sendable {
    /// 某个直连地址通过了探测（TCP + 钉扎 TLS 握手）。
    case direct(String)
    /// 直连全部不通、且这台电脑有远程能力：这次新建连接走中继（RFC 0001 §7.2 第 5 条）。
    case relay(RemoteTarget)
    /// 直连全部不通且没有可用的中继（没配对远程能力 / 远程字段不全）。
    /// 这是 `forget` 的真正依据；中继错误码不是（见 `RelayRouteDisposition`）。
    case noDirectAvailable

    /// 直连地址；不是直连档时为 nil。
    public var directAddress: String? {
        if case .direct(let address) = self { return address }
        return nil
    }

    /// 是否走中继。`relayTarget` 在非中继档为 nil。
    public var isRelay: Bool {
        if case .relay = self { return true }
        return false
    }

    /// 中继目标；非中继档为 nil。**只含路由元数据，不含任何密钥。**
    public var relayTarget: RemoteTarget? {
        if case .relay(let target) = self { return target }
        return nil
    }
}

/// 中继档的目标：由上层已保存的远程能力（`DeviceRemoteInfo`）裁剪而来，只带路由所需的最小信息。
///
/// **明确不放进这里**：`relayKey`（会合密钥）与 `outerCertificatePin`。选路结果会被日志、诊断
/// 和 SwiftUI 状态持有；密钥只允许在建立连接的那一刻从 `DLSecurity` 的 SecureStore 现取
/// （`HostStore` 的 `host.<hostId>.remote.relayKey`），外层指纹同理。
public struct RemoteTarget: Equatable, Sendable {
    /// 中继地址（`DeviceRemoteInfo.endpoint`，RFC 0001 §6.3 的 `e`）。
    public var endpoint: String
    /// routeId（`DeviceRemoteInfo.routeId`，字段 `r`）。Relay 按它查在线控制连接。
    public var routeId: String
    /// 设备 handle（`DeviceRemoteInfo.deviceHandle`，字段 `h`）。
    public var deviceHandle: String

    public init(endpoint: String, routeId: String, deviceHandle: String) {
        self.endpoint = endpoint
        self.routeId = routeId
        self.deviceHandle = deviceHandle
    }

    /// 从已保存的远程能力裁剪；三个路由字段任一为空（或全空白）即视为没有可用的中继目标。
    /// 密钥字段不参与，也不会被读取。
    public init?(remote: DeviceRemoteInfo?) {
        guard let remote else { return nil }
        let endpoint = remote.endpoint?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let routeId = remote.routeId?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let handle = remote.deviceHandle?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !endpoint.isEmpty, !routeId.isEmpty, !handle.isEmpty else { return nil }
        self.init(endpoint: endpoint, routeId: routeId, deviceHandle: handle)
    }
}

/// 中继拒绝 / 关闭码的分类（RFC 0001 §5.7 表 + §7.4）。
///
/// **为什么放在选路模块里**：这个分类决定「能不能清选路缓存、能不能换路、能不能动凭据」，
/// 而那三件事全是选路模块的职责。中继转来的任何 code 都**不删凭据**
/// （§7.4：Relay 可能被攻破，伪造 code 就能清空用户凭据）。
public enum RelayRouteDisposition: Equatable, Sendable {
    /// 这条路暂时满了：`DEVICE_LIMIT` / `SERVER_BUSY` / `RATE_LIMITED`。
    /// 只退避重试：**不切路、不清选路缓存、不删凭据**，走的路还是中继。
    case backoff
    /// 会合失败：`BAD_MAC` / `UNKNOWN_KEY`。
    /// 只提示「远程凭据无效，请回到局域网或重新扫码」；**不删凭据、不清选路缓存、不切路**。
    case rePair
    /// 插件本地服务未就绪：`LOCAL_UNAVAILABLE`。显示「电脑上的服务暂不可用」，退避重试。
    case localUnavailable
    /// 电脑不在线：`4003 ROUTE_OFFLINE`。可以提示电脑离线，但不动凭据。
    case hostOffline
    /// 外层 WSS / TLS 或握手超时（`OPEN_TIMEOUT`）：中继这类基础设施暂时走不通，本轮记失败。
    case relayUnreachable
    /// 没有已知语义的中继 code：**按不可信处理**，只提示，什么都不动。
    case unknown
    /// 硬停止，只有内层 TLS 的明确答复才算：内层证书不符（§7.2 第 8 条）或插件答复已吊销。
    /// 这是唯一允许清凭据的路径，且它**不来自中继 code**。
    case hardStop

    /// 是否要按 §7.2 第 7 条在这一轮换到别的路重试（仅限路由建立阶段）。
    /// 被拒绝的三类「暂时满了」一律为 false：换路只会把同一台电脑换到另一条同样满的路。
    public var allowsPathSwitch: Bool {
        switch self {
        case .backoff, .localUnavailable, .rePair: false
        case .hostOffline, .relayUnreachable, .unknown, .hardStop: true
        }
    }

    /// 是否只能提示、不能据此删改本机凭据（RFC 0001 §7.4）。
    /// 除 `.hardStop` 外全部为 true：`.hardStop` 也必须来自内层 TLS 的明确答复，不来自中继 code。
    public var isUntrustedAdvisory: Bool {
        switch self {
        case .hardStop: false
        case .backoff, .rePair, .localUnavailable, .hostOffline, .relayUnreachable, .unknown: true
        }
    }

    /// 是否要清这台电脑的选路缓存。**只有电脑确实离线时可以**：中继的其余拒绝都可能是
    /// 恶意 Relay 伪造的，清缓存等于让它单方面把用户从直连赶到中继。
    public var invalidatesCachedRoute: Bool { self == .hostOffline }

    /// 是否允许删改本机凭据。只有内层 TLS 的明确答复可以。
    public var allowsCredentialChange: Bool { self == .hardStop }

    /// 中继 code（`error.code` 或 WebSocket close reason）→ 分类。
    /// 大小写与两侧空白都容错；未知 code 落 `.unknown`，**绝不**落成任何会动凭据的分类。
    public init(relayCode code: String) {
        switch code.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() {
        case "DEVICE_LIMIT", "SERVER_BUSY", "RATE_LIMITED": self = .backoff
        case "BAD_MAC", "UNKNOWN_KEY": self = .rePair
        case "LOCAL_UNAVAILABLE": self = .localUnavailable
        case "ROUTE_OFFLINE": self = .hostOffline
        case "OPEN_TIMEOUT", "RELAY_UNREACHABLE", "TLS_FAILED": self = .relayUnreachable
        default: self = .unknown
        }
    }
}

/// Android `RouteSelector` 的对等实现：候选顺序、直连缓存（30 秒）、中继决策（15 秒）、
/// 网络代与同主机单飞。
///
/// 三种档位与 TTL（RFC 0001 §7.2 第 2 条 / PLAN I5.4）：
/// - **直连**：主地址 → Tailscale，都是 LAN 语义，成功地址缓存 **30 秒**；
/// - **中继**：直连全部不通且有远程能力时选的档，**中继档只缓存 15 秒**，这样用户回到家里 /
///   公司网络后能在 15 秒内切回直连，而直连成功后要整整 30 秒才会重新探测；
/// - **无效**：没有中继能力（或远程字段不全）时的 `.noDirectAvailable`。
///
/// 仍然遵守原有的边界：本 actor 不持有 token、不发请求、不改变已有 HTTP/SSE 连接。
/// 中继这一档同理——它**只产出「该走中继」和「用哪个 route/handle」这个决定**，不建立连接、
/// 不消费会合密钥；真正连中继的是上层传输层。网络变化（`onNetworkChanged`）作废全部档位，
/// 包括中继档（§7.2.6）。
public actor RouteSelector {
    public typealias Probe = @Sendable (String) async -> Bool
    public typealias Clock = @Sendable () -> TimeInterval

    /// 直连（主地址 / Tailscale）成功地址的缓存时长。
    public static let directTTL: TimeInterval = 30
    /// 中继决策的缓存时长，与直连的 30 秒刻意不同（RFC 0001 §7.2 第 2 条）。
    public static let relayTTL: TimeInterval = 15
    public private(set) var currentGeneration: UInt64 = 0

    /// 选路缓存的一格：要么是某个直连地址，要么是「走中继」。
    private struct Cached: Equatable {
        let generation: UInt64
        let kind: Kind
        let until: TimeInterval

        enum Kind: Equatable {
            case direct(String)
            case relay(RemoteTarget)
        }

        var selection: RouteSelection {
            switch kind {
            case .direct(let address): .direct(address)
            case .relay(let target): .relay(target)
            }
        }

        var ttl: TimeInterval {
            switch kind {
            case .direct: RouteSelector.directTTL
            case .relay: RouteSelector.relayTTL
            }
        }
    }

    private struct Flight {
        let id: UUID
        let generation: UInt64
        let revision: UInt64
        let task: Task<RouteSelection, Never>
    }

    private let clock: Clock
    private var cache: [String: Cached] = [:]
    private var lastRoutes: [String: RouteSelection] = [:]
    private var flights: [String: Flight] = [:]
    private var revisions: [String: UInt64] = [:]

    /// 默认使用进程单调时钟，不受系统日期调整影响；测试注入虚拟秒数。
    /// `ProcessInfo.systemUptime` 不在 Apple “需说明理由的 API”启动时间清单里（清单是 `mach_absolute_time` 与读取启动时间的 `sysctl`），所以不写进 `PrivacyInfo.xcprivacy`。
    public init(clock: @escaping Clock = { ProcessInfo.processInfo.systemUptime }) {
        self.clock = clock
    }

    /// Android `Host.directLanUrls()`：主地址在前，非空且不同的备用地址在后。
    /// `tailnetUrl` 已由配对层保存；这里不扫描二维码、不按地址类型重新排序。
    public nonisolated static func directCandidates(for host: PairedHost) -> [String] {
        let spare = host.tailnetUrl?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let primaryIdentity = host.primaryUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/")).lowercased()
        let spareIdentity = spare.trimmingCharacters(in: CharacterSet(charactersIn: "/")).lowercased()
        if spare.isEmpty || spareIdentity == primaryIdentity { return [host.primaryUrl] }
        return [host.primaryUrl, spare]
    }

    /// 这台电脑有没有可用的中继目标：`DeviceRemoteInfo` 的 e / r / h 三个路由字段必须齐全。
    /// 会合密钥与外层指纹不参与判定，缺密钥只在真正连中继时才知道（`HostStore` 的凭据对账负责）。
    public nonisolated static func relayTarget(for host: PairedHost) -> RemoteTarget? {
        RemoteTarget(remote: host.remote)
    }

    /// 按输入顺序探测（PLAN I3.7 / Android `firstDirect`，不是首配 `firstReachable` 的并行竞速）。
    /// 直连候选全部不通时，若 `relay` 非空则返回 `.relay`（§7.2 第 5 条），否则 `.noDirectAvailable`。
    /// - Parameters:
    ///   - key: 同一已配对电脑的稳定 key（通常为 hostId）。地址/身份变更后调用方需 `forget`。
    ///   - candidates: 主地址在前、Tailscale 备用在后；空白跳过，其余地址原样交给探测。
    ///   - relay: 这台电脑的中继目标；**只做选择，不在这里建立任何中继连接**。传 nil 表示没有远程能力。
    ///   - probe: 必须仅做有超时预算的 TCP + 钉扎 TLS 握手，不发 HTTP、token 或配对码。
    ///     两个地址校验同一插件证书；证书不符算该候选不通，不能删除凭据。
    /// 同 key 的调用共享第一位调用者的探测；取消一位等待者不取消共享探测。
    public func select(
        key: String,
        candidates: [String],
        relay: RemoteTarget? = nil,
        probe: @escaping Probe
    ) async -> RouteSelection {
        while true {
            if let hit = fresh(key) { return hit.selection }
            let flight: Flight
            if let existing = flights[key] {
                flight = existing
            } else {
                let task = Task<RouteSelection, Never> {
                    for address in candidates {
                        if address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { continue }
                        if await probe(address) { return .direct(address) }
                    }
                    if let relay { return .relay(relay) }
                    return .noDirectAvailable
                }
                flight = Flight(
                    id: UUID(), generation: currentGeneration, revision: revisions[key, default: 0], task: task)
                flights[key] = flight
            }
            let result = await flight.task.value
            let stillValid = flight.generation == currentGeneration && flight.revision == revisions[key, default: 0]
            // 只有一个等待者提交结果。网络变化 / forget / noteSuccess 后的旧任务不能填回缓存。
            if flights[key]?.id == flight.id {
                flights.removeValue(forKey: key)
                if stillValid {
                    switch result {
                    case .direct(let address): remember(key, kind: .direct(address))
                    case .relay(let target): remember(key, kind: .relay(target))
                    case .noDirectAvailable: break  // 失败不写负缓存：以后恢复的直连不能被锁住。
                    }
                }
            }
            if stillValid { return result }
            // 先等旧探测结束，再按新代重探；即使换网，同一台电脑也不并行探测两次。
        }
    }

    /// Android `lanAddress`：仅当前网络代、尚未过期的成功直连地址。
    /// 中继档下为 nil——上层看到 nil 且 `isRelaying` 为真，就知道该去连中继而不是直连地址。
    public func lanAddress(key: String) -> String? {
        guard case .direct(let address)? = fresh(key)?.kind else { return nil }
        return address
    }

    /// 当前是否处于中继档（未过期的中继决策）。与 `lanAddress` 互斥。
    public func isRelaying(key: String) -> Bool {
        guard let hit = fresh(key) else { return false }
        if case .relay = hit.kind { return true }
        return false
    }

    /// 当前中继档的目标；非中继档为 nil。**只含路由元数据，不含会合密钥。**
    public func relayTarget(key: String) -> RemoteTarget? {
        guard case .relay(let target)? = fresh(key)?.kind else { return nil }
        return target
    }

    /// Android `noteSuccess` / `remember`：实际成功的路成为缓存选择，TTL 从此次成功起算。
    /// 不传结果时只沿用仍然有效的缓存；已过期 / 已 forget 的选择不复活。
    /// 若上层持续调用此方法会续期，因此应由上层决定何时需要更新实际成功的路。
    /// - Parameter selection: 直连成功传 `.direct(地址)`；中继真的连上了传 `.relay(...)`，这时按 15 秒续期。
    ///   `.noDirectAvailable` 不写缓存（它是「没有路」，不是一条成功的路）。
    public func noteSuccess(key: String, selection: RouteSelection? = nil) {
        guard let selection = selection ?? fresh(key)?.selection else { return }
        switch selection {
        case .direct(let address):
            guard !address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
            lastRoutes[key] = .direct(address)
            revisions[key, default: 0] += 1
            remember(key, kind: .direct(address))
        case .relay(let target):
            lastRoutes[key] = .relay(target)
            revisions[key, default: 0] += 1
            remember(key, kind: .relay(target))
        case .noDirectAvailable:
            return
        }
    }

    /// Android `noteSuccess` 的直连便捷重载：等价于 `noteSuccess(key:selection:.direct(address))`。
    /// 地址为空则什么都不做（旧行为）。
    public func noteSuccess(key: String, address: String) {
        noteSuccess(key: key, selection: .direct(address))
    }

    /// Android `lastRoute`：最后实际成功的路，仅供显示；不是当前可用性或新连接的选路依据。
    public func lastRoute(key: String) -> RouteSelection? { lastRoutes[key] }

    /// Android `forget`：下一次重新探测，也使正在等待的旧探测结果失效。
    ///
    /// **调用约束（RFC 0001 §7.4）**：中继错误码（`UNKNOWN_KEY` / `BAD_MAC` / `DEVICE_LIMIT` /
    /// `SERVER_BUSY` / `RATE_LIMITED` …）一律**不得**触发这个方法，见 `RelayRouteDisposition`。
    /// 只有直连/中继都建立不起来（`.noDirectAvailable`）、内层 TLS 明确答复、或用户显式重新配对时才可以。
    public func forget(key: String) {
        cache.removeValue(forKey: key)
        revisions[key, default: 0] += 1
    }

    /// 网络代 +1，所有主机缓存作废（**直连与中继档都作废**，RFC 0001 §7.2 第 1 条 / §7.2.6）；
    /// 不强行迁移正在传输的连接。
    public func onNetworkChanged() {
        currentGeneration += 1
        cache.removeAll()
    }

    private func fresh(_ key: String) -> Cached? {
        guard let hit = cache[key], hit.generation == currentGeneration, hit.until > clock() else { return nil }
        return hit
    }

    private func remember(_ key: String, kind: Cached.Kind) {
        let draft = Cached(generation: currentGeneration, kind: kind, until: clock())
        cache[key] = Cached(generation: currentGeneration, kind: kind, until: clock() + draft.ttl)
    }
}
