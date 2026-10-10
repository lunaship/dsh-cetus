import DLCore
import Foundation
import Testing

@testable import Cetus

/// Fake ActivityKit surface. No real activity is ever requested in tests.
private actor FakeActivityAdapter: LiveActivityAdapting {
    private(set) var started: [LiveActivityContent] = []
    private(set) var updated: [LiveActivityContent] = []
    private(set) var ended: [LiveActivityContent] = []
    private var handles: [LiveActivityHandle] = []
    private var nextID = 1
    var failStart = false
    /// Instances the system "already holds" at launch.
    private var preexisting: [LiveActivityHandle] = []

    /// Seeding happens through the actor so the test never mutates isolated
    /// state from a nonisolated context.
    func seedPreexisting(_ handles: [LiveActivityHandle]) {
        preexisting = handles
    }

    func start(content: LiveActivityContent) async throws -> LiveActivityHandle {
        if failStart { throw LiveActivityError.notSupported }
        started.append(content)
        let handle = LiveActivityHandle(id: "activity-\(nextID)", sessionRef: content.sessionRef)
        nextID += 1
        handles.append(handle)
        return handle
    }

    func update(handle: LiveActivityHandle, content: LiveActivityContent) async throws {
        updated.append(content)
    }

    func end(handle: LiveActivityHandle, content: LiveActivityContent) async throws {
        ended.append(content)
        handles.removeAll { $0.id == handle.id }
    }

    func running() async -> [LiveActivityHandle] {
        handles + preexisting
    }

    func startedTitles() -> [String?] {
        started.map(\.title)
    }

    func counts() -> (started: Int, updated: Int, ended: Int) {
        (started.count, updated.count, ended.count)
    }
}

@Suite("Live Activity controller")
struct LiveActivityControllerTests {
    private static let started = Date(timeIntervalSince1970: 1_728_000_000)

    private static func controller(
        adapter: FakeActivityAdapter, enabled: Bool = true
    ) -> LiveActivityController {
        LiveActivityController(adapter: adapter, isEnabled: { enabled }, now: { started })
    }

    @Test("同一会话重复 start 只创建一个实例")
    func startDoesNotDuplicate() async {
        let adapter = FakeActivityAdapter()
        let controller = Self.controller(adapter: adapter)

        let first = await controller.start(
            hostRef: "host", sessionRef: "sess", phase: .running, step: 1, startedAt: Self.started, waitingCount: 0)
        let second = await controller.start(
            hostRef: "host", sessionRef: "sess", phase: .running, step: 2, startedAt: Self.started, waitingCount: 0)

        #expect(first?.id == second?.id)
        let counts = await adapter.counts()
        #expect(counts.started == 1)
        #expect(counts.updated == 1)
    }

    @Test("恢复已运行实例：不重复创建，孤儿活动被结束")
    func restoreReattachesAndEndsOrphans() async {
        let adapter = FakeActivityAdapter()
        let controller = Self.controller(adapter: adapter)

        let handle = await controller.start(
            hostRef: "host", sessionRef: "sess", phase: .running, step: 1, startedAt: Self.started, waitingCount: 0)
        #expect(handle != nil)

        // Simulate a relaunch: a fresh controller over the same ActivityKit state.
        let relaunched = FakeActivityAdapter()
        await relaunched.seedPreexisting([LiveActivityHandle(id: "activity-1", sessionRef: "sess")])
        let restored = Self.controller(adapter: relaunched)

        let restoredHandles = await restored.restore()
        #expect(restoredHandles.count == 1)

        // Updating must reuse the restored instance, not start a second one.
        _ = await restored.update(
            hostRef: "host", sessionRef: "sess", phase: .approval, step: 3, startedAt: Self.started, waitingCount: 2)
        let counts = await relaunched.counts()
        #expect(counts.started == 0)
        #expect(counts.updated == 1)
    }

    @Test("完成/失败/停止都会结束活动")
    func terminalPhasesEndTheActivity() async {
        for terminal in [LiveActivityPhase.ended] {
            let adapter = FakeActivityAdapter()
            let controller = Self.controller(adapter: adapter)
            _ = await controller.start(
                hostRef: "host", sessionRef: "sess", phase: .running, step: 1, startedAt: Self.started,
                waitingCount: 0)

            // A terminal phase resolves to `.ended`, which yields nil content.
            _ = await controller.update(
                hostRef: "host", sessionRef: "sess", phase: terminal, step: 1, startedAt: Self.started,
                waitingCount: 0)

            let counts = await adapter.counts()
            #expect(counts.ended == 1)
            let remaining = await controller.activeHandles()
            #expect(remaining.isEmpty)
        }
    }

    @Test("关闭开关会结束活动且不再启动")
    func disabledEndsAndDoesNotStart() async {
        let adapter = FakeActivityAdapter()
        let controller = Self.controller(adapter: adapter, enabled: false)

        let handle = await controller.start(
            hostRef: "host", sessionRef: "sess", phase: .running, step: 1, startedAt: Self.started, waitingCount: 0)
        #expect(handle == nil)
        let counts = await adapter.counts()
        #expect(counts.started == 0)
    }

    @Test("结束未知会话是幂等的")
    func endIsIdempotent() async {
        let adapter = FakeActivityAdapter()
        let controller = Self.controller(adapter: adapter)

        await controller.end(sessionRef: "never-started")
        await controller.end(sessionRef: "never-started")

        let counts = await adapter.counts()
        #expect(counts.ended == 0)
    }

    @Test("内容状态不含标题等敏感字段，锁屏文案通用")
    func contentStateHasNoTitle() {
        let content = LiveActivityPolicy.content(
            enabled: true, hostRef: "host", sessionRef: "sess", phase: .approval, step: 2,
            startedAt: Self.started, waitingCount: 1)
        #expect(content != nil)
        #expect(content?.lockScreenStatus == "有一项任务需要处理")

        let running = LiveActivityPolicy.content(
            enabled: true, hostRef: "host", sessionRef: "sess", phase: .running, step: 0,
            startedAt: Self.started, waitingCount: 0)
        #expect(running?.lockScreenStatus == "任务进行中")
        #expect(running?.step == 1)
    }

    @Test("超过阈值显示为陈旧，不再假装运行中")
    func stalenessAfterThreshold() {
        let base = Date(timeIntervalSince1970: 1_728_000_000)
        #expect(LiveActivityStaleness.isStale(lastUpdated: base, now: base.addingTimeInterval(60)) == false)
        #expect(
            LiveActivityStaleness.isStale(
                lastUpdated: base, now: base.addingTimeInterval(LiveActivityStaleness.threshold + 1)) == true)
    }

    @Test("标题随活动启动传入，内容状态仍只有五个字段")
    func titleTravelsInAttributesOnly() async throws {
        let adapter = FakeActivityAdapter()
        let controller = Self.controller(adapter: adapter)
        _ = await controller.start(
            hostRef: "host", sessionRef: "sess", phase: .running, step: 1, startedAt: Self.started, waitingCount: 0,
            title: "整理发布说明")
        let titles = await adapter.startedTitles()
        #expect(titles == ["整理发布说明"])

        let state = CetusActivityAttributes.ContentState(
            state: "running", step: 1, startedAt: Self.started, waitingCount: 0, sessionRef: "sess")
        let stateKeys = try #require(
            try JSONSerialization.jsonObject(with: JSONEncoder().encode(state)) as? [String: Any]
        ).keys
        #expect(Set(stateKeys) == ["state", "step", "startedAt", "waitingCount", "sessionRef"])

        let attributes = CetusActivityAttributes(hostRef: "host", title: "整理发布说明")
        let attributeKeys = try #require(
            try JSONSerialization.jsonObject(with: JSONEncoder().encode(attributes)) as? [String: Any]
        ).keys
        #expect(Set(attributeKeys) == ["hostRef", "title"])
    }

    @Test("锁屏标题压成一行、去空白、过长截断")
    func titleIsCleaned() {
        #expect(LiveActivityTitle.clean(nil) == nil)
        #expect(LiveActivityTitle.clean("  \n ") == nil)
        #expect(LiveActivityTitle.clean(" a\n b ") == "a b")
        let long = String(repeating: "长", count: 200)
        let cleaned = LiveActivityTitle.clean(long)
        #expect(cleaned?.count == LiveActivityTitle.maxCharacters)
        #expect(cleaned?.hasSuffix("…") == true)
    }
}
