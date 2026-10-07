import DLModels
import DLNet
import DLSecurity
import Foundation
import Network
import Testing

/// I3.7 / I5.4：时钟、探测与路径事件全部注入；不开端口、不 sleep、不访问真实 UserDefaults。
/// **不连中继**：relay 只是传入的 `RemoteTarget` 值，本文件没有任何网络调用，也不碰 `relay.dshlinks.com`。
/// Android 的蜂窝捷径、clockOffset 用例仍属于后续子项，不在这里翻译。
/// `host without remote never probes` 是布尔 order 旧重载；本项按候选地址重载探测所有直连。
/// `unknown lan capability keeps probing` / `lan capable true probes` 在本项均对应普通候选探测，
/// 不增加阶段 5 的 lanCapable 参数。PairClient.firstReachable 的竞速留给首配，不用于日常顺序选路。
struct RouteSelectorContractTests {
    private static let primary = "https://10.0.0.2:18640"
    private static let tailnet = "https://100.64.0.2:18640"
    private static let candidates = [primary, tailnet]

    private final class TestClock: @unchecked Sendable {
        private let lock = NSLock()
        private var instant: TimeInterval = 1000

        func now() -> TimeInterval { lock.withLock { instant } }

        func advance(_ seconds: TimeInterval) {
            lock.withLock { instant += seconds }
        }
    }

    private actor Probe {
        private var reachable: Set<String>
        private(set) var seen: [String] = []

        init(reachable: Set<String> = []) { self.reachable = reachable }

        func call(_ address: String) -> Bool {
            seen.append(address)
            return reachable.contains(address)
        }

        func setReachable(_ addresses: Set<String>) { reachable = addresses }
    }

    private actor ProbeLatch {
        private var started = false
        private var startWaiters: [CheckedContinuation<Void, Never>] = []
        private var releaseWaiter: CheckedContinuation<Void, Never>?
        private(set) var calls = 0

        func call() async -> Bool {
            calls += 1
            started = true
            for waiter in startWaiters { waiter.resume() }
            startWaiters.removeAll()
            await withCheckedContinuation { releaseWaiter = $0 }
            return true
        }

        func waitForStart() async {
            if !started { await withCheckedContinuation { startWaiters.append($0) } }
        }

        func release() {
            releaseWaiter?.resume()
            releaseWaiter = nil
        }
    }

    private final class FakePathSource: NetworkPathEventSource, Sendable {
        let stream: AsyncStream<NetworkPathEvent>
        let continuation: AsyncStream<NetworkPathEvent>.Continuation

        init() {
            (stream, continuation) = AsyncStream.makeStream()
        }

        func events() -> AsyncStream<NetworkPathEvent> { stream }
        func cancel() { continuation.finish() }
    }

    @MainActor
    private final class ExplanationStorage: LocalNetworkPermissionStorage {
        var hasShownExplanation = false
    }

    /// 中继目标只做值传递，本文件里没有任何东西会去连它（不碰 relay.dshlinks.com）。
    private static let relayTarget = RemoteTarget(
        endpoint: "wss://relay.example.invalid:8443", routeId: "route-1", deviceHandle: "handle-1")

    /// 远程能力齐全的已配对电脑（e / r / h 三个路由字段）。
    private static func hostWithRemote(
        primary: String = RouteSelectorContractTests.primary,
        tailnet: String? = RouteSelectorContractTests.tailnet,
        remote: DeviceRemoteInfo? = DeviceRemoteInfo(
            endpoint: "wss://relay.example.invalid:8443",
            routeId: "route-1",
            deviceHandle: "handle-1",
            relayKey: "k-1",
            outerCertificatePin: "pin-1")
    ) -> PairedHost {
        PairedHost(
            hostId: "k", name: "host", primaryUrl: primary, tailnetUrl: tailnet, certFingerprint: "", remote: remote,
            pairedAt: 0)
    }

    @Test("Android: LAN reachable picks LAN first and caches it for 30 seconds")
    func primaryWinsAndCacheExpiresAtThirtySeconds() async {
        let clock = TestClock()
        let selector = RouteSelector(clock: { clock.now() })
        let probe = Probe(reachable: Set(Self.candidates))
        let first = await selector.select(key: "k", candidates: Self.candidates, probe: { await probe.call($0) })
        #expect(first == .direct(Self.primary))
        #expect(await selector.lanAddress(key: "k") == Self.primary)
        clock.advance(29.999)
        await probe.setReachable([Self.tailnet])
        let cached = await selector.select(key: "k", candidates: Self.candidates, probe: { await probe.call($0) })
        #expect(cached == first)
        #expect(await probe.seen == [Self.primary])
        clock.advance(0.001)
        #expect(await selector.lanAddress(key: "k") == nil)
        let expired = await selector.select(key: "k", candidates: Self.candidates, probe: { await probe.call($0) })
        #expect(expired == .direct(Self.tailnet))
        #expect(await probe.seen == [Self.primary, Self.primary, Self.tailnet])
    }

    @Test("Android: primary fails and tailnet succeeds")
    func tailnetFallbackIsCachedForThirtySeconds() async {
        let clock = TestClock()
        let selector = RouteSelector(clock: { clock.now() })
        let probe = Probe(reachable: [Self.tailnet])
        let first = await selector.select(key: "k", candidates: Self.candidates, probe: { await probe.call($0) })
        #expect(first == .direct(Self.tailnet))
        #expect(await probe.seen == Self.candidates)
        #expect(await selector.lanAddress(key: "k") == Self.tailnet)
        clock.advance(15)
        _ = await selector.select(key: "k", candidates: Self.candidates, probe: { await probe.call($0) })
        #expect(await probe.seen == Self.candidates)  // Tailscale 是 LAN，不能误用 REMOTE 的 15 秒 TTL。
        clock.advance(15)
        _ = await selector.select(key: "k", candidates: Self.candidates, probe: { await probe.call($0) })
        #expect(await probe.seen == Self.candidates + Self.candidates)
    }

    @Test("Android: both direct addresses fail then remote（阶段 5 前返回无直连）")
    func failureAndBlankCandidates() async {
        let selector = RouteSelector()
        let probe = Probe()
        let candidates = ["", " \n\t"] + Self.candidates + [""]
        let result = await selector.select(key: "k", candidates: candidates, probe: { await probe.call($0) })
        #expect(result == .noDirectAvailable)
        #expect(await probe.seen == Self.candidates)
        #expect(await selector.lanAddress(key: "k") == nil)
        // PLAN 只缓存成功地址：失败不能把以后恢复的直连锁在负缓存里。
        await probe.setReachable([Self.primary])
        #expect(
            await selector.select(key: "k", candidates: Self.candidates, probe: { await probe.call($0) })
                == .direct(Self.primary))
        #expect(await probe.seen == Self.candidates + [Self.primary])
        #expect(
            await selector.select(key: "empty", candidates: [], probe: { await probe.call($0) }) == .noDirectAvailable)
    }

    @Test("Android: network change drops cached decisions / drops the cached direct address")
    func networkGenerationInvalidatesEveryHost() async {
        let selector = RouteSelector()
        let probe = Probe(reachable: [Self.tailnet])
        for key in ["a", "b"] {
            _ = await selector.select(key: key, candidates: Self.candidates, probe: { await probe.call($0) })
        }
        #expect(await selector.currentGeneration == 0)
        await selector.onNetworkChanged()
        #expect(await selector.currentGeneration == 1)
        #expect(await selector.lanAddress(key: "a") == nil)
        #expect(await selector.lanAddress(key: "b") == nil)
        await probe.setReachable([Self.primary])
        for key in ["a", "b"] {
            #expect(
                await selector.select(key: key, candidates: Self.candidates, probe: { await probe.call($0) })
                    == .direct(Self.primary))
        }
        #expect(await probe.seen == Self.candidates + Self.candidates + [Self.primary, Self.primary])
    }

    @Test("Android: concurrent requests share a single probe")
    func concurrentSelectionsShareProbe() async {
        let selector = RouteSelector()
        let latch = ProbeLatch()
        await withTaskGroup(of: RouteSelection.self) { group in
            for _ in 0..<8 {
                group.addTask {
                    await selector.select(key: "k", candidates: Self.candidates, probe: { _ in await latch.call() })
                }
            }
            await latch.waitForStart()
            await latch.release()
            for await result in group { #expect(result == .direct(Self.primary)) }
        }
        #expect(await latch.calls == 1)
    }

    @Test("Android: forget / success on the fallback route becomes the cached choice")
    func forgetAndNoteSuccess() async {
        let clock = TestClock()
        let selector = RouteSelector(clock: { clock.now() })
        let probe = Probe(reachable: [Self.primary])
        _ = await selector.select(key: "k", candidates: Self.candidates, probe: { await probe.call($0) })
        await selector.forget(key: "k")
        #expect(await selector.lanAddress(key: "k") == nil)
        _ = await selector.select(key: "k", candidates: Self.candidates, probe: { await probe.call($0) })
        #expect(await probe.seen == [Self.primary, Self.primary])
        await selector.forget(key: "k")
        await selector.noteSuccess(key: "k", address: Self.tailnet)
        #expect(await selector.lanAddress(key: "k") == Self.tailnet)
        #expect(await selector.lastRoute(key: "k") == .direct(Self.tailnet))
        #expect(
            await selector.select(key: "k", candidates: Self.candidates, probe: { await probe.call($0) })
                == .direct(Self.tailnet))
        clock.advance(29)
        await selector.noteSuccess(key: "k")  // Android remember：沿用仍有效的直连地址，并续期。
        clock.advance(1)
        #expect(await selector.lanAddress(key: "k") == Self.tailnet)
        clock.advance(29)
        #expect(await selector.lanAddress(key: "k") == nil)
        await selector.noteSuccess(key: "k")  // 已过期的未知地址不得复活。
        await selector.noteSuccess(key: "k", address: " \n")
        #expect(await selector.lanAddress(key: "k") == nil)
        await selector.forget(key: "k")
        await selector.onNetworkChanged()
        // Android lastRoutes 只记录实际成功，不随选路 TTL / forget / 换网抹掉历史展示值。
        #expect(await selector.lastRoute(key: "k") == .direct(Self.tailnet))
        #expect(await selector.lastRoute(key: "unknown") == nil)
    }

    @Test("网络变化 / forget 发生在探测中：旧结果不得重新填入缓存")
    func invalidationDuringProbe() async {
        for networkChange in [true, false] {
            let selector = RouteSelector()
            let latch = ProbeLatch()
            let probe = Probe(reachable: [Self.tailnet])
            let selection = Task {
                await selector.select(key: "k", candidates: Self.candidates) { address in
                    if await latch.calls == 0 { return await latch.call() }
                    return await probe.call(address)
                }
            }
            await latch.waitForStart()
            if networkChange { await selector.onNetworkChanged() } else { await selector.forget(key: "k") }
            await latch.release()
            #expect(await selection.value == .direct(Self.tailnet))
            #expect(await selector.lanAddress(key: "k") == Self.tailnet)
            #expect(await probe.seen == Self.candidates)
        }
    }

    @Test("在途探测不能覆盖实际连接 noteSuccess 的结果")
    func successDuringProbe() async {
        let selector = RouteSelector()
        let latch = ProbeLatch()
        let selection = Task {
            await selector.select(key: "k", candidates: Self.candidates, probe: { _ in await latch.call() })
        }
        await latch.waitForStart()
        await selector.noteSuccess(key: "k", address: Self.tailnet)
        await latch.release()
        #expect(await selection.value == .direct(Self.tailnet))
        #expect(await selector.lanAddress(key: "k") == Self.tailnet)
    }

    @Test("Android Host.directLanUrls / PairClient: qr tailnet address is stored beside the primary")
    func candidatesFollowStoredHostOrder() {
        var host = PairedHost(
            hostId: "k", name: "host", primaryUrl: Self.primary, tailnetUrl: " \(Self.tailnet) ",
            certFingerprint: "", pairedAt: 0)
        #expect(RouteSelector.directCandidates(for: host) == Self.candidates)
        host.tailnetUrl = "\(Self.primary.uppercased())/"
        #expect(RouteSelector.directCandidates(for: host) == [Self.primary])
        host.tailnetUrl = " \n"
        #expect(RouteSelector.directCandidates(for: host) == [Self.primary])
        host.tailnetUrl = nil
        #expect(RouteSelector.directCandidates(for: host) == [Self.primary])
    }

    @Test("Android NetworkChangeTest: 首次回调只登记不清池；后续切网 / onLost / 接口变化作废选路")
    func fakePathEventsInvalidateRoutes() async {
        let selector = RouteSelector()
        await selector.noteSuccess(key: "k", address: Self.primary)
        let source = FakePathSource()
        let observer = NetworkPathObserver(selector: selector, source: source)
        let observation = Task { await observer.run() }
        source.continuation.yield(.initial)
        source.cancel()
        await observation.value
        #expect(await selector.currentGeneration == 0)
        #expect(await selector.lanAddress(key: "k") == Self.primary)

        let changedSource = FakePathSource()
        let changedObserver = NetworkPathObserver(selector: selector, source: changedSource)
        let changes = Task { await changedObserver.run() }
        changedSource.continuation.yield(.initial)
        for _ in ["Wi-Fi → cellular", "interface changed", "onLost", "same interface route changed"] {
            changedSource.continuation.yield(.changed)
        }
        changedSource.cancel()
        await changes.value
        #expect(await selector.currentGeneration == 4)
        #expect(await selector.lanAddress(key: "k") == nil)
        let probe = Probe(reachable: [Self.tailnet])
        #expect(
            await selector.select(key: "k", candidates: Self.candidates, probe: { await probe.call($0) })
                == .direct(Self.tailnet))
        #expect(await probe.seen == Self.candidates)
    }

    @Test("取消路径观察任务会结束事件源；不依赖真实 NWPathMonitor")
    func cancellationStopsObservation() async {
        let source = FakePathSource()
        let selector = RouteSelector()
        let observer = NetworkPathObserver(selector: selector, source: source)
        let task = Task { await observer.run() }
        task.cancel()
        await task.value
        guard case .terminated = source.continuation.yield(.changed) else {
            Issue.record("取消后事件源应已结束")
            return
        }
        #expect(await selector.currentGeneration == 0)
    }

    @Test("本地网络说明页：仅首次，标记跨 gate 实例保存；说明页不是系统授权状态")
    @MainActor
    func explanationIsPersistedThroughInjectedStorage() {
        let storage = ExplanationStorage()
        let gate = LocalNetworkPermissionGate(storage: storage)
        #expect(gate.requiresExplanation(for: Self.primary))
        #expect(!gate.requiresExplanation(for: Self.tailnet))
        #expect(!storage.hasShownExplanation)
        gate.markExplanationShown()
        #expect(!gate.requiresExplanation(for: Self.primary))
        #expect(!LocalNetworkPermissionGate(storage: storage).requiresExplanation(for: "https://host.local"))
    }

    @Test("Android isTailnetUrl + iOS 平台决定：Tailscale IPv4 / IPv6 / MagicDNS 经 VPN 不走本地网络说明页")
    func addressClassification() {
        for address in [
            Self.tailnet, "https://100.127.255.255", "https://[fd7a:115c:a1e0::8]",
            "https://host.tail.ts.net", "https://HOST.TAIL.TS.NET./", "https://127.0.0.1", "https://[::1]",
            "https://8.8.8.8",
        ] {
            #expect(!LocalNetworkPermissionGate.mayRequireLocalNetworkAccess(address), "\(address)")
        }
        for address in [
            Self.primary, "https://192.168.1.1", "https://172.16.0.1", "https://169.254.1.1",
            "https://[fe80::1%25en0]", "https://[fd00::1]", "https://host.local.", "https://host",
            "https://host.example", "https://[::ffff:192.168.1.1]",
        ] {
            #expect(LocalNetworkPermissionGate.mayRequireLocalNetworkAccess(address), "\(address)")
        }
    }

    @Test("Apple TN3179：连接路径 localNetworkDenied / DNS PolicyDenied 是拒绝推断；普通离线等不是")
    func permissionDenialIsAnInference() {
        #expect(
            LocalNetworkPermissionGate.isDenialInferred(
                error: NWError.posix(.ENETDOWN), for: Self.primary, unsatisfiedReason: .localNetworkDenied))
        #expect(
            LocalNetworkPermissionGate.isDenialInferred(error: NWError.dns(-65570), for: "https://host.local"))
        let wrapped = URLError(.notConnectedToInternet, userInfo: [NSUnderlyingErrorKey: NWError.dns(-65570)])
        #expect(LocalNetworkPermissionGate.isDenialInferred(error: wrapped, for: "https://host.local"))
        for error: any Error in [
            URLError(.notConnectedToInternet), URLError(.timedOut), URLError(.cannotConnectToHost),
            NWError.posix(.EACCES), NWError.posix(.EPERM), NWError.dns(-65537),
            HostConnectionError.certificateChanged,
        ] {
            #expect(!LocalNetworkPermissionGate.isDenialInferred(error: error, for: Self.primary))
        }
        #expect(
            !LocalNetworkPermissionGate.isDenialInferred(
                error: URLError(.notConnectedToInternet), for: Self.primary, unsatisfiedReason: .wifiDenied))
        #expect(
            !LocalNetworkPermissionGate.isDenialInferred(
                error: NWError.dns(-65570), for: Self.tailnet, unsatisfiedReason: .localNetworkDenied))
    }

    // MARK: - I5.4 中继档（RFC 0001 §7.2 / §5.7 / §7.4）

    @Test("§7.2 顺序：直连任一候选成功就走直连，绝不回落中继")
    func directSuccessNeverFallsBackToRelay() async {
        for reachable in [Self.primary, Self.tailnet] {
            let clock = TestClock()
            let selector = RouteSelector(clock: { clock.now() })
            let probe = Probe(reachable: [reachable])
            let relay = RouteSelector.relayTarget(for: Self.hostWithRemote())
            let result = await selector.select(
                key: "k", candidates: Self.candidates, relay: relay, probe: { await probe.call($0) })
            #expect(result == .direct(reachable))
            #expect(await selector.lanAddress(key: "k") == reachable)
            #expect(await selector.isRelaying(key: "k") == false)
            #expect(await selector.relayTarget(key: "k") == nil)
            #expect(await selector.lastRoute(key: "k") == nil, "选路不等于实际成功，lastRoute 只在 noteSuccess 后才有值")
        }
    }

    @Test("§7.2 第 5 条：直连全失败且主机有远程能力 → 中继档，并带上 route / handle")
    func bothDirectFailThenRelay() async {
        let selector = RouteSelector()
        let probe = Probe()
        let relay = RouteSelector.relayTarget(for: Self.hostWithRemote())
        #expect(relay == Self.relayTarget)
        let result = await selector.select(
            key: "k", candidates: Self.candidates, relay: relay, probe: { await probe.call($0) })
        #expect(result == .relay(Self.relayTarget))
        // 三个路由字段原样透传；会合密钥 / 外层指纹不进选路结果。
        #expect(result.relayTarget?.routeId == "route-1")
        #expect(result.relayTarget?.deviceHandle == "handle-1")
        #expect(await selector.isRelaying(key: "k"))
        #expect(await selector.relayTarget(key: "k") == Self.relayTarget)
        #expect(await selector.lanAddress(key: "k") == nil, "中继档不是直连地址，lanAddress 必须为 nil")
        #expect(await probe.seen == Self.candidates)
    }

    @Test("§7.2 第 5 条：没有远程能力时不返回中继，仍是 noDirectAvailable")
    func noRemoteCapabilityStaysOffRelay() async {
        let selector = RouteSelector()
        let probe = Probe()
        #expect(RouteSelector.relayTarget(for: Self.hostWithRemote(remote: nil)) == nil)
        #expect(
            await selector.select(key: "k", candidates: Self.candidates, relay: nil, probe: { await probe.call($0) })
                == .noDirectAvailable)
        #expect(await selector.isRelaying(key: "k") == false)
        #expect(await selector.relayTarget(key: "k") == nil)
        #expect(await selector.lanAddress(key: "k") == nil)
        // 中继档也没有负缓存：下一次直连恢复就立刻选直连。
        await probe.setReachable([Self.primary])
        #expect(
            await selector.select(key: "k", candidates: Self.candidates, relay: nil, probe: { await probe.call($0) })
                == .direct(Self.primary))
    }

    @Test("§7.2 第 2 条：中继档 15 秒到期即重探，direct 的 30 秒 TTL 不适用于它")
    func relayDecisionExpiresAtFifteenSeconds() async {
        let clock = TestClock()
        let selector = RouteSelector(clock: { clock.now() })
        let probe = Probe()
        let relay = RouteSelector.relayTarget(for: Self.hostWithRemote())
        #expect(
            await selector.select(key: "k", candidates: Self.candidates, relay: relay, probe: { await probe.call($0) })
                == .relay(Self.relayTarget))
        #expect(await probe.seen == Self.candidates)
        clock.advance(14.999)
        #expect(
            await selector.select(key: "k", candidates: Self.candidates, relay: relay, probe: { await probe.call($0) })
                == .relay(Self.relayTarget))
        #expect(await probe.seen == Self.candidates, "15 秒内命中中继缓存，不再探测")
        clock.advance(0.001)
        #expect(await selector.isRelaying(key: "k") == false, "中继缓存到期")
        #expect(
            await selector.select(key: "k", candidates: Self.candidates, relay: relay, probe: { await probe.call($0) })
                == .relay(Self.relayTarget))
        #expect(await probe.seen == Self.candidates + Self.candidates, "到期后重探全部直连候选")
        // 对照：直连档在 15 秒时仍然有效（否则用户会被 15 秒一次地反复探测）。
        await probe.setReachable([Self.primary])
        #expect(
            await selector.select(key: "k", candidates: Self.candidates, relay: relay, probe: { await probe.call($0) })
                == .direct(Self.primary))
        clock.advance(15)
        #expect(await selector.lanAddress(key: "k") == Self.primary)
    }

    @Test("§7.2 第 2 条：中继真的连上后 noteSuccess(.relay) 按 15 秒续期，lastRoute 记中继")
    func relayNoteSuccessRenewsForFifteenSeconds() async {
        let clock = TestClock()
        let selector = RouteSelector(clock: { clock.now() })
        await selector.noteSuccess(key: "k", selection: .relay(Self.relayTarget))
        #expect(await selector.lastRoute(key: "k") == .relay(Self.relayTarget))
        #expect(await selector.isRelaying(key: "k"))
        clock.advance(14)
        await selector.noteSuccess(key: "k")  // 不传结果 = 沿用仍有效的中继档并续期
        clock.advance(14)
        #expect(await selector.isRelaying(key: "k"))
        clock.advance(1)
        #expect(await selector.isRelaying(key: "k") == false)
        // 已过期的档不得复活；noDirectAvailable 永远不写缓存。
        await selector.noteSuccess(key: "k")
        #expect(await selector.isRelaying(key: "k") == false)
        await selector.noteSuccess(key: "k", selection: .noDirectAvailable)
        #expect(await selector.isRelaying(key: "k") == false)
        #expect(await selector.lastRoute(key: "k") == .relay(Self.relayTarget), "lastRoute 是历史展示值，不被 TTL 抹掉")
    }

    @Test("§7.2.6：网络变化同时作废中继档")
    func networkChangeInvalidatesRelayDecision() async {
        let selector = RouteSelector()
        let probe = Probe()
        let relay = RouteSelector.relayTarget(for: Self.hostWithRemote())
        for key in ["a", "b"] {
            _ = await selector.select(
                key: key, candidates: Self.candidates, relay: relay, probe: { await probe.call($0) })
        }
        #expect(await selector.isRelaying(key: "a"))
        #expect(await selector.isRelaying(key: "b"))
        await selector.onNetworkChanged()
        #expect(await selector.isRelaying(key: "a") == false)
        #expect(await selector.isRelaying(key: "b") == false)
        #expect(await selector.relayTarget(key: "a") == nil)
        // 换网后直连恢复：立即回到直连，不用等 15 秒。
        await probe.setReachable([Self.tailnet])
        #expect(
            await selector.select(key: "a", candidates: Self.candidates, relay: relay, probe: { await probe.call($0) })
                == .direct(Self.tailnet))
        #expect(await selector.isRelaying(key: "a") == false)
    }

    @Test("§7.4：relay 档下 DEVICE_LIMIT 只退避重试 —— 不切路、不清缓存、不 forget")
    func deviceLimitOnRelayDoesNotForget() async {
        let clock = TestClock()
        let selector = RouteSelector(clock: { clock.now() })
        let probe = Probe()
        let relay = RouteSelector.relayTarget(for: Self.hostWithRemote())
        #expect(
            await selector.select(key: "k", candidates: Self.candidates, relay: relay, probe: { await probe.call($0) })
                == .relay(Self.relayTarget))
        let before = await probe.seen

        for code in ["DEVICE_LIMIT", "SERVER_BUSY", "RATE_LIMITED"] {
            let disposition = RelayRouteDisposition(relayCode: code)
            #expect(disposition == .backoff, "\(code)")
            #expect(!disposition.allowsPathSwitch, "\(code) 是「这条路暂时满了」，不能换路")
            #expect(!disposition.allowsCredentialChange, "\(code) 不得删凭据")
            #expect(!disposition.invalidatesCachedRoute, "\(code) 不得清选路缓存")
            #expect(disposition.isUntrustedAdvisory, "\(code) 中继转来的码只能提示")
        }

        // App 按上面的判定做事：退避重试期间什么都不动。
        #expect(await selector.isRelaying(key: "k"), "缓存还在，路还是中继")
        #expect(await selector.lanAddress(key: "k") == nil)
        #expect(
            await selector.select(key: "k", candidates: Self.candidates, relay: relay, probe: { await probe.call($0) })
                == .relay(Self.relayTarget))
        #expect(await probe.seen == before, "没有 forget，就不该重新探测 —— 也不会被赶回直连")
        #expect(await selector.lastRoute(key: "k") == nil, "拒绝不是成功，不写 lastRoute")
    }

    @Test("§7.4：UNKNOWN_KEY / BAD_MAC 只提示，不删凭据、不清缓存、不切路")
    func unknownKeyAndBadMacAreAdvisoryOnly() async {
        let selector = RouteSelector()
        let probe = Probe()
        let relay = RouteSelector.relayTarget(for: Self.hostWithRemote())
        _ = await selector.select(key: "k", candidates: Self.candidates, relay: relay, probe: { await probe.call($0) })
        let before = await probe.seen
        for code in ["UNKNOWN_KEY", "BAD_MAC", " unknown_key "] {
            let disposition = RelayRouteDisposition(relayCode: code)
            #expect(disposition == .rePair, "\(code)")
            #expect(!disposition.allowsCredentialChange, "\(code) 只提示「重新扫码」，不删凭据")
            #expect(!disposition.allowsPathSwitch, "\(code) 不换路重试")
            #expect(!disposition.invalidatesCachedRoute, "\(code) 不清选路缓存")
        }
        #expect(await selector.isRelaying(key: "k"))
        #expect(await probe.seen == before)
    }

    @Test("§5.7 表：九类 code 的解析与未知码兜底（未知一律按不可信处理）")
    func relayCodeMapping() {
        #expect(RelayRouteDisposition(relayCode: "DEVICE_LIMIT") == .backoff)
        #expect(RelayRouteDisposition(relayCode: "SERVER_BUSY") == .backoff)
        #expect(RelayRouteDisposition(relayCode: "RATE_LIMITED") == .backoff)
        #expect(RelayRouteDisposition(relayCode: "UNKNOWN_KEY") == .rePair)
        #expect(RelayRouteDisposition(relayCode: "BAD_MAC") == .rePair)
        #expect(RelayRouteDisposition(relayCode: "LOCAL_UNAVAILABLE") == .localUnavailable)
        #expect(RelayRouteDisposition(relayCode: "ROUTE_OFFLINE") == .hostOffline)
        #expect(RelayRouteDisposition(relayCode: "OPEN_TIMEOUT") == .relayUnreachable)
        for code in ["", "  ", "CLOCK_SKEW", "REPLAY", "PROTOCOL_ERROR", "SOMETHING_NEW"] {
            #expect(RelayRouteDisposition(relayCode: code) == .unknown, "\(code)")
        }
        // §7.4：无论哪个中继 code，都不得删改本机凭据；只有内层 TLS 的明确答复（hardStop）可以。
        for code in [
            "DEVICE_LIMIT", "SERVER_BUSY", "RATE_LIMITED", "UNKNOWN_KEY", "BAD_MAC", "LOCAL_UNAVAILABLE",
            "ROUTE_OFFLINE", "OPEN_TIMEOUT", "", "WHATEVER",
        ] {
            #expect(!RelayRouteDisposition(relayCode: code).allowsCredentialChange, "\(code)")
        }
        #expect(RelayRouteDisposition.hardStop.allowsCredentialChange)
        #expect(!RelayRouteDisposition.hardStop.isUntrustedAdvisory)
        // 只有「电脑离线」允许清缓存，其余中继码都不许。
        #expect(RelayRouteDisposition.hostOffline.invalidatesCachedRoute)
        #expect(!RelayRouteDisposition.unknown.invalidatesCachedRoute)
        #expect(!RelayRouteDisposition.rePair.invalidatesCachedRoute)
    }

    @Test("RemoteTarget：只取 e / r / h，缺一即无中继；密钥字段不进选路结果")
    func remoteTargetOmitsSecretsAndRequiresAllRouteFields() {
        #expect(RemoteTarget(remote: nil) == nil)
        #expect(RemoteTarget(remote: DeviceRemoteInfo()) == nil)
        let full = DeviceRemoteInfo(
            endpoint: "wss://relay.example.invalid:8443", routeId: "route-1", deviceHandle: "handle-1",
            relayKey: "k-1", outerCertificatePin: "pin-1")
        #expect(RemoteTarget(remote: full) == Self.relayTarget)
        #expect(RemoteTarget(remote: DeviceRemoteInfo(endpoint: " ", routeId: "r", deviceHandle: "h")) == nil)
        #expect(RemoteTarget(remote: DeviceRemoteInfo(endpoint: "e", routeId: "\t", deviceHandle: "h")) == nil)
        #expect(RemoteTarget(remote: DeviceRemoteInfo(endpoint: "e", routeId: "r", deviceHandle: "  ")) == nil)
        // 只有密钥、没有路由字段 → 仍然不是可用的中继目标。
        #expect(RemoteTarget(remote: DeviceRemoteInfo(relayKey: "k-1", outerCertificatePin: "pin-1")) == nil)
        // 全空白 Trim 后逐字段判断。
        #expect(
            RemoteTarget(remote: DeviceRemoteInfo(endpoint: " wss://e ", routeId: " r ", deviceHandle: " h "))
                == RemoteTarget(endpoint: "wss://e", routeId: "r", deviceHandle: "h"))
        // PairedHost 侧的取值路径与 RouteSelection 的便捷取值一致。
        let host = Self.hostWithRemote()
        #expect(RouteSelector.relayTarget(for: host) == Self.relayTarget)
        #expect(RouteSelection.relay(Self.relayTarget).relayTarget == Self.relayTarget)
        #expect(RouteSelection.relay(Self.relayTarget).isRelay)
        #expect(RouteSelection.direct(Self.primary).relayTarget == nil)
        #expect(RouteSelection.direct(Self.primary).directAddress == Self.primary)
        #expect(RouteSelection.noDirectAvailable.directAddress == nil)
    }

    @Test("单飞同样适用于中继档：同 key 并发只探测一轮")
    func concurrentSelectionsShareRelayFallbackProbe() async {
        let selector = RouteSelector()
        let latch = ProbeLatch()
        let relay = RouteSelector.relayTarget(for: Self.hostWithRemote())
        await withTaskGroup(of: RouteSelection.self) { group in
            for _ in 0..<8 {
                group.addTask {
                    await selector.select(
                        key: "k", candidates: Self.candidates, relay: relay, probe: { _ in await latch.call() })
                }
            }
            await latch.waitForStart()
            await latch.release()
            for await result in group { #expect(result == .relay(Self.relayTarget)) }
        }
        #expect(await latch.calls == 1)
        #expect(await selector.isRelaying(key: "k"))
    }

    @Test("§7.2 第 7 条：建立阶段失败 in-flight 时换网/forget，中继档也不得回填缓存")
    func relayDecisionNotCachedWhenInvalidatedDuringProbe() async {
        for networkChange in [true, false] {
            let selector = RouteSelector()
            let latch = ProbeLatch()
            let relay = RouteSelector.relayTarget(for: Self.hostWithRemote())
            let selection = Task {
                await selector.select(key: "k", candidates: Self.candidates, relay: relay) { _ in
                    await latch.call()
                }
            }
            await latch.waitForStart()
            if networkChange { await selector.onNetworkChanged() } else { await selector.forget(key: "k") }
            await latch.release()
            // 这一次仍然返回中继（结果有效），但旧代的中继决策不能落进新代的缓存。
            #expect(await selection.value == .relay(Self.relayTarget))
            #expect(await selector.isRelaying(key: "k") == false)
            #expect(await selector.relayTarget(key: "k") == nil)
        }
    }
}
