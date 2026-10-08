import DLNet
import DLSecurity
import Foundation
import Testing

/// 阶段 5：relay 选路（RFC 0001 §7.2 第 2/4/5 条）。
///
/// 本文件只覆盖**新增的远程分支**，不重复 `RouteSelectorContractTests` 已有的直连行为
/// （那 14 例必须继续全绿，是向后兼容的证据）。
///
/// 全部用注入时钟 + 注入探测，不开端口、不 sleep、不碰真实网络。
struct RemoteRouteSelectionTests {
    private static let primary = "https://10.0.0.2:18640"
    private static let tailnet = "https://100.64.0.2:18640"

    private final class TestClock: @unchecked Sendable {
        private let lock = NSLock()
        private var instant: TimeInterval = 1000
        func now() -> TimeInterval { lock.withLock { instant } }
        func advance(_ seconds: TimeInterval) { lock.withLock { instant += seconds } }
    }

    private actor Probe {
        private var reachable: Set<String>
        private(set) var seen: [String] = []
        init(reachable: Set<String> = []) { self.reachable = reachable }
        func call(_ address: String) -> Bool {
            seen.append(address)
            return reachable.contains(address)
        }
    }

    // MARK: - §7.2 第 5 条：直连全挂 + 有远程能力 → .remote

    @Test func allDirectCandidatesFailWithRemoteCapabilitySelectsRemote() async {
        let clock = TestClock()
        let selector = RouteSelector(clock: { clock.now() })
        let probe = Probe()

        let result = await selector.select(
            key: "h", candidates: [Self.primary, Self.tailnet], hasRemote: true
        ) { await probe.call($0) }

        #expect(result == .remote)
        let seen = await probe.seen
        #expect(seen == [Self.primary, Self.tailnet], "有远程能力也要先把直连候选探完")
    }

    /// 最关键的一条：没有远程能力时**绝不能**返回 `.remote`。
    /// 否则上层会去连一个不存在的中继，把「离线」误报成「远程可用」。
    @Test func allDirectCandidatesFailWithoutRemoteCapabilityStaysNoDirectAvailable() async {
        let clock = TestClock()
        let selector = RouteSelector(clock: { clock.now() })
        let probe = Probe()

        let result = await selector.select(
            key: "h", candidates: [Self.primary, Self.tailnet], hasRemote: false
        ) { await probe.call($0) }

        #expect(result == .noDirectAvailable)
    }

    @Test func defaultHasRemoteKeepsLegacyBehaviour() async {
        let clock = TestClock()
        let selector = RouteSelector(clock: { clock.now() })
        let probe = Probe()
        // 不传 hasRemote（既有调用点的写法）必须等价于 false。
        let result = await selector.select(key: "h", candidates: [Self.primary]) { await probe.call($0) }
        #expect(result == .noDirectAvailable)
    }

    // MARK: - 直连优先：LAN / Tailscale 可用时不该走远程

    @Test func reachablePrimaryStillWinsOverRemote() async {
        let clock = TestClock()
        let selector = RouteSelector(clock: { clock.now() })
        let probe = Probe(reachable: [Self.primary])

        let result = await selector.select(
            key: "h", candidates: [Self.primary, Self.tailnet], hasRemote: true
        ) { await probe.call($0) }

        #expect(result == .direct(Self.primary))
        let seen = await probe.seen
        #expect(seen == [Self.primary], "第一个候选就通，不该继续探")
    }

    @Test func unreachablePrimaryFallsBackToTailnetBeforeRemote() async {
        let clock = TestClock()
        let selector = RouteSelector(clock: { clock.now() })
        let probe = Probe(reachable: [Self.tailnet])

        let result = await selector.select(
            key: "h", candidates: [Self.primary, Self.tailnet], hasRemote: true
        ) { await probe.call($0) }

        #expect(result == .direct(Self.tailnet), "Tailscale 也是直连，优先于远程")
    }

    // MARK: - §7.2 第 2 条：TTL 与缓存

    @Test func remoteResultIsCachedForFifteenSecondsThenReprobes() async {
        let clock = TestClock()
        let selector = RouteSelector(clock: { clock.now() })
        let probe = Probe()

        let first = await selector.select(key: "h", candidates: [Self.primary], hasRemote: true) {
            await probe.call($0)
        }
        #expect(first == .remote)
        #expect(await probe.seen.count == 1)

        // 缓存命中：不再探测
        let cached = await selector.select(key: "h", candidates: [Self.primary], hasRemote: true) {
            await probe.call($0)
        }
        #expect(cached == .remote)
        #expect(await probe.seen.count == 1, "15 秒内应命中缓存，不重复探测")

        // 走到 16 秒：REMOTE TTL 是 15 秒，必须过期重探
        clock.advance(16)
        _ = await selector.select(key: "h", candidates: [Self.primary], hasRemote: true) {
            await probe.call($0)
        }
        #expect(await probe.seen.count == 2, "REMOTE 缓存 15 秒后必须重探")
    }

    /// §7.2 第 2 条：REMOTE TTL（15 秒）比 LAN（30 秒）短，回家后能较快切回 LAN。
    @Test func remoteCacheExpiresSoonerThanDirectCache() async {
        let clock = TestClock()
        let selector = RouteSelector(clock: { clock.now() })

        // 主机 d：LAN 可达 → 建立**直连**缓存（30 秒 TTL）
        let lanProbe = Probe(reachable: [Self.primary])
        _ = await selector.select(key: "d", candidates: [Self.primary], hasRemote: true) {
            await lanProbe.call($0)
        }
        // 主机 r：LAN 不可达 + 有远程能力 → 建立**远程**缓存（15 秒 TTL）
        let downProbe = Probe()
        _ = await selector.select(key: "r", candidates: [Self.primary], hasRemote: true) {
            await downProbe.call($0)
        }

        #expect(await selector.lanAddress(key: "d") == Self.primary)
        #expect(await selector.lanAddress(key: "r") == nil, "远程结果不带地址")

        // 16 秒后：远程（15s）已过期必须重探；直连（30s）仍应命中缓存。
        clock.advance(16)
        let remoteReprobe = await selector.select(key: "r", candidates: [Self.primary], hasRemote: true) {
            await downProbe.call($0)
        }
        #expect(remoteReprobe == .remote)
        #expect(await downProbe.seen.count == 2, "远程缓存 15 秒后必须重探")

        let directStillCached = await selector.select(key: "d", candidates: [Self.primary], hasRemote: true) {
            await lanProbe.call($0)
        }
        #expect(directStillCached == .direct(Self.primary))
        #expect(await lanProbe.seen.count == 1, "直连缓存在 16 秒时仍有效（30 秒 TTL），不该重探")
    }

    // MARK: - 网络切换：晚到结果不得覆盖新代

    @Test func networkChangeDropsRemoteCacheAndLateResultDoesNotOverwrite() async {
        let clock = TestClock()
        let selector = RouteSelector(clock: { clock.now() })
        let probe = Probe()

        _ = await selector.select(key: "h", candidates: [Self.primary], hasRemote: true) { await probe.call($0) }
        // `select` 只返回结果、**不写** lastRoute：lastRoute 由实际连接成功后的
        // `noteSuccess` / `noteSuccessRemote` 登记（"最后实际成功的路"）。
        #expect(await selector.lastRoute(key: "h") == nil)

        // 换网 → 缓存作废；这次 LAN 通了，结果必须是直连，且不得复用旧代的远程判定。
        await selector.onNetworkChanged()
        let probe2 = Probe(reachable: [Self.primary])
        let after = await selector.select(key: "h", candidates: [Self.primary], hasRemote: true) {
            await probe2.call($0)
        }
        #expect(after == .direct(Self.primary), "网络代变化后必须重探，不能用旧代的远程缓存")
        #expect(after != .remote)
    }

    @Test func forgetDropsRemoteCache() async {
        let clock = TestClock()
        let selector = RouteSelector(clock: { clock.now() })
        let probe = Probe()

        _ = await selector.select(key: "h", candidates: [Self.primary], hasRemote: true) { await probe.call($0) }
        #expect(await probe.seen.count == 1)

        await selector.forget(key: "h")
        _ = await selector.select(key: "h", candidates: [Self.primary], hasRemote: true) { await probe.call($0) }
        #expect(await probe.seen.count == 2, "forget 后必须重探")
    }

    // MARK: - noteSuccessRemote 语义边界

    @Test func noteSuccessRemoteCachesSelectionWithoutClaimingAddress() async {
        let clock = TestClock()
        let selector = RouteSelector(clock: { clock.now() })
        let probe = Probe()

        await selector.noteSuccessRemote(key: "h")
        #expect(await selector.lastRoute(key: "h") == .remote)
        #expect(await selector.lanAddress(key: "h") == nil)

        // 远程缓存应被复用，不触发探测
        let result = await selector.select(key: "h", candidates: [Self.primary], hasRemote: true) {
            await probe.call($0)
        }
        #expect(result == .remote)
        #expect(await probe.seen.isEmpty, "已登记的远程结果应直接命中缓存")
    }

    @Test func noteSuccessWithoutAddressDoesNotResurrectRemoteAsAddress() async {
        let clock = TestClock()
        let selector = RouteSelector(clock: { clock.now() })
        await selector.noteSuccessRemote(key: "h")
        // 不带地址调用 noteSuccess：不应把远程变成「有地址的直连」
        await selector.noteSuccess(key: "h")
        #expect(await selector.lanAddress(key: "h") == nil)
        #expect(await selector.lastRoute(key: "h") == .remote)
    }
}
