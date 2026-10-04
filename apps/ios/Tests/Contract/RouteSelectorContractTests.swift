import DLNet
import DLSecurity
import Foundation
import Network
import Testing

/// I3.7：时钟、探测与路径事件全部注入；不开端口、不 sleep、不访问真实 UserDefaults。
/// Android 的 REMOTE TTL、远程/蜂窝捷径、clockOffset 用例属于阶段 5，不在这里翻译。
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
}
