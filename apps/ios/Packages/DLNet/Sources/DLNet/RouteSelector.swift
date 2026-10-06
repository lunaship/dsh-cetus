import DLSecurity
import Foundation

/// I3.7 的直连结果。阶段 5 才增加 relay；这里绝不建立中继连接或返回虚假的远程成功。
public enum RouteSelection: Equatable, Sendable {
    case direct(String)
    case noDirectAvailable
}

/// Android `RouteSelector` 的直连部分：候选顺序、LAN 地址缓存、网络代与同主机单飞。
///
/// Tailscale 也是直连，沿用 LAN 的 30 秒 TTL；REMOTE 的 15 秒 TTL 留到 I5.4。
/// 本 actor 不持有 token、不发请求、不改变已有 HTTP/SSE 连接。调用方在新建连接前使用结果，
/// 只在建立阶段失败时 `forget`；实际连接成功时可用 `noteSuccess` 更新地址/续期。
public actor RouteSelector {
    public typealias Probe = @Sendable (String) async -> Bool
    public typealias Clock = @Sendable () -> TimeInterval

    public static let directTTL: TimeInterval = 30
    public private(set) var currentGeneration: UInt64 = 0

    private struct Cached {
        let generation: UInt64
        let address: String
        let until: TimeInterval
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

    /// 按输入顺序探测（PLAN I3.7 / Android `firstDirect`，不是首配 `firstReachable` 的并行竞速）。
    /// - Parameters:
    ///   - key: 同一已配对电脑的稳定 key（通常为 hostId）。地址/身份变更后调用方需 `forget`。
    ///   - candidates: 主地址在前、Tailscale 备用在后；空白跳过，其余地址原样交给探测。
    ///   - probe: 必须仅做有超时预算的 TCP + 钉扎 TLS 握手，不发 HTTP、token 或配对码。
    ///     两个地址校验同一插件证书；证书不符算该候选不通，不能删除凭据。
    /// 同 key 的调用共享第一位调用者的探测；取消一位等待者不取消共享探测。
    public func select(key: String, candidates: [String], probe: @escaping Probe) async -> RouteSelection {
        while true {
            if let hit = fresh(key) { return .direct(hit.address) }
            let flight: Flight
            if let existing = flights[key] {
                flight = existing
            } else {
                let task = Task<RouteSelection, Never> {
                    for address in candidates {
                        if address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { continue }
                        if await probe(address) { return .direct(address) }
                    }
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
                if stillValid, case .direct(let address) = result { remember(key, address: address) }
            }
            if stillValid { return result }
            // 先等旧探测结束，再按新代重探；即使换网，同一台电脑也不并行探测两次。
        }
    }

    /// Android `lanAddress`：仅当前网络代、尚未过期的成功直连地址。
    public func lanAddress(key: String) -> String? { fresh(key)?.address }

    /// Android `noteSuccess` / `remember`：实际成功地址成为缓存选择，TTL 从此次成功起算。
    /// 不传地址时只沿用仍有效的直连地址；已过期/已 forget 的地址不复活。
    /// 若上层持续调用此方法会续期，因此应由上层决定何时需要更新实际成功的路。
    public func noteSuccess(key: String, address: String? = nil) {
        guard let address = address ?? fresh(key)?.address,
            !address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { return }
        lastRoutes[key] = .direct(address)
        revisions[key, default: 0] += 1
        remember(key, address: address)
    }

    /// Android `lastRoute`：最后实际成功的路，仅供显示；不是当前可用性或新连接的选路依据。
    public func lastRoute(key: String) -> RouteSelection? { lastRoutes[key] }

    /// Android `forget`：下一次重新探测，也使正在等待的旧探测结果失效。
    public func forget(key: String) {
        cache.removeValue(forKey: key)
        revisions[key, default: 0] += 1
    }

    /// 网络代 +1，所有主机缓存作废；不强行迁移正在传输的连接（RFC 0001 §7.2.6）。
    public func onNetworkChanged() {
        currentGeneration += 1
        cache.removeAll()
    }

    private func fresh(_ key: String) -> Cached? {
        guard let hit = cache[key], hit.generation == currentGeneration, hit.until > clock() else { return nil }
        return hit
    }

    private func remember(_ key: String, address: String) {
        cache[key] = Cached(generation: currentGeneration, address: address, until: clock() + Self.directTTL)
    }
}
