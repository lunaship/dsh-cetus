import DLCore
import DLModels
import DLNet
import Foundation
import Testing

/// I3.5 合同测试：SSE 解析与客户端状态机。
///
/// 「本地假服务器」= 脚本化的 `SSETransport`，不开真实端口：把
/// `testdata/mobile-contract/{session-stream,session-stream-resync,host-events}.json` 的帧
/// 还原成 SSE 线格式（`id:` / `event:` / `data:` + 空行）喂给解析器与客户端，断言事件序列、
/// id 与游标。所有时长注入成毫秒级，测试确定、快速。
struct SSEClientContractTests {
    // MARK: - 测试基建

    /// fixtures 帧的原样形状（`{event, id, data}`，`data` 留给按 `event` 解码）。
    private struct RawFrame: Decodable {
        var event: String
        var id: Int?
        var data: JSONValue
    }

    /// 固定测试地址（静态字符串，预构造一次）。
    private static let eventsURL = URL(string: "https://host.example:18640/dsh-link/mobile/events")!

    private static func streamURL(_ afterSeq: Int, sessionId: String = "sess-demo") -> URL {
        URL(
            string: "https://host.example:18640/dsh-link/mobile/sessions/\(sessionId)/stream"
                + "?afterSeq=\(afterSeq)&caps=sync2,multiQuestion,requestState"
        )!
    }

    /// 退避/心跳全部毫秒级，测试秒级完成。
    private static let fastTiming = SSEClient.Timing(
        heartbeatTimeout: .milliseconds(300),
        initialBackoff: .milliseconds(20),
        maxBackoff: .milliseconds(120),
        jitterFraction: 0
    )

    private final class LockBox<Value: Sendable>: @unchecked Sendable {
        private let lock = NSLock()
        private var value: Value

        init(_ value: Value) {
            self.value = value
        }

        var current: Value {
            lock.withLock { value }
        }

        func set(_ newValue: Value) {
            lock.withLock { value = newValue }
        }

        /// 测试里用脚本等一个测试侧条件（例如「已提交游标再断流」）。
        func waitUntilTrue(timeout: Duration = .seconds(3)) async where Value == Bool {
            let clock = ContinuousClock()
            let deadline = clock.now + timeout
            while !current, clock.now < deadline {
                try? await Task.sleep(for: .milliseconds(2))
            }
        }
    }

    private final class Counter: @unchecked Sendable {
        private let lock = NSLock()
        private var value = 0

        func bump() {
            lock.withLock { value += 1 }
        }

        var current: Int {
            lock.withLock { value }
        }
    }

    private final class Recorder<Value: Sendable>: @unchecked Sendable {
        private let lock = NSLock()
        private var items: [Value] = []

        func append(_ item: Value) {
            lock.withLock { items.append(item) }
        }

        var snapshot: [Value] {
            lock.withLock { items }
        }

        var count: Int {
            lock.withLock { items.count }
        }
    }

    /// 一条连接的剧本：状态码 + 逐行喂行。`hold` 喂完不结束（模拟僵死/长连接）。
    private struct Script: Sendable {
        var status: Int?
        var feed: @Sendable (AsyncThrowingStream<String, any Error>.Continuation) async -> Void

        static func lines(_ lines: [String], status: Int? = 200) -> Script {
            Script(status: status) { continuation in
                for line in lines {
                    continuation.yield(line)
                }
                continuation.finish()
            }
        }

        static func hold(_ lines: [String] = [], status: Int? = 200) -> Script {
            Script(status: status) { continuation in
                for line in lines {
                    continuation.yield(line)
                }
                // 挂死：不 yield、不 finish；测试取消连接时由 onTermination 取消本任务。
                try? await Task.sleep(for: .seconds(30))
                continuation.finish()
            }
        }
    }

    /// 脚本化假传输：每次 `open` 弹出下一个剧本，记录请求与取消。
    private final class ScriptedSSETransport: SSETransport, @unchecked Sendable {
        let openedRequests = Recorder<URLRequest>()
        let cancelledCount = Counter()

        private let lock = NSLock()
        private var scripts: [Script]

        init(scripts: [Script]) {
            self.scripts = scripts
        }

        func open(_ request: URLRequest) async throws -> SSEConnection {
            openedRequests.append(request)
            let script = lock.withLock {
                scripts.isEmpty ? Script.hold() : scripts.removeFirst()
            }
            let (stream, continuation) = AsyncThrowingStream<String, any Error>.makeStream()
            let feedTask = Task { await script.feed(continuation) }
            continuation.onTermination = { [weak self] _ in
                feedTask.cancel()
                self?.cancelledCount.bump()
            }
            return SSEConnection(statusCode: script.status, lines: stream, onCancel: { continuation.finish() })
        }
    }

    /// 收集客户端下行输出。
    private final class OutputCollector: @unchecked Sendable {
        let box = Recorder<SSEClient.Output>()
        private var collector: Task<Void, Never>?

        func start(_ client: SSEClient) {
            collector = Task {
                for await output in client.events {
                    box.append(output)
                }
            }
        }

        func events() async -> [SSEEvent] {
            let outputs = box.snapshot
            return outputs.compactMap { output -> SSEEvent? in
                if case .event(let event) = output { return event }
                return nil
            }
        }

        func hasTerminated() async -> Bool {
            box.snapshot.contains { output in
                if case .terminated = output { return true }
                return false
            }
        }
    }

    /// 轮询等条件成立（毫秒级小睡，不做真实长等待）。
    private func waitUntil(timeout: Duration = .seconds(3), _ condition: @Sendable () async -> Bool) async -> Bool {
        let clock = ContinuousClock()
        let deadline = clock.now + timeout
        while true {
            if await condition() { return true }
            if clock.now >= deadline { return await condition() }
            try? await Task.sleep(for: .milliseconds(5))
        }
    }

    // MARK: - fixtures 读取与线格式还原

    private static func repoRoot() -> URL {
        // …/apps/ios/Tests/Contract/<file>.swift 逐级上溯 5 层到仓库根
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }

    private static func fixtureFrames(_ name: String) throws -> [RawFrame] {
        let url = repoRoot()
            .appendingPathComponent("testdata")
            .appendingPathComponent("mobile-contract")
            .appendingPathComponent(name)
        return try JSONDecoder().decode([RawFrame].self, from: Data(contentsOf: url))
    }

    /// 把 fixtures 帧 `{event, id, data}` 还原成插件实际发送的 SSE 线格式。
    /// 插件侧（`src/index.js` / `src/host-events.js`）：会话流帧不带 `id:`；主机事件流帧带 `id: <seq>`。
    private static func wire(_ frames: [RawFrame]) throws -> [String] {
        let encoder = JSONEncoder()
        var lines: [String] = []
        for frame in frames {
            if let id = frame.id {
                lines.append("id: \(id)")
            }
            lines.append("event: \(frame.event)")
            lines.append("data: \(String(decoding: try encoder.encode(frame.data), as: UTF8.self))")
            lines.append("")
        }
        return lines
    }

    /// 主机事件流风格的线格式帧（`id:` + `session/state`）。
    private static func hostEventLines(ids: [Int]) -> [String] {
        ids.flatMap { id -> [String] in
            [
                "id: \(id)",
                "event: session/state",
                "data: {\"type\":\"session/state\",\"sessionId\":\"sess-x\",\"state\":\"running\",\"origin\":\"user\",\"seq\":\(id)}",
                "",
            ]
        }
    }

    /// 会话流风格的线格式帧（无 `id:` 行，seq 在 data 里）。
    private static func sessionMessageLines(seqs: [Int]) -> [String] {
        seqs.flatMap { seq -> [String] in
            [
                "event: message",
                "data: {\"seq\":\(seq),\"type\":\"assistant/chunk\",\"time\":1767225603000,\"data\":{\"chunk\":{\"type\":\"text\",\"text\":\"t\(seq)\"}}}",
                "",
            ]
        }
    }

    // MARK: - SSELineParser（读 fixtures 的合同测试）

    @Test("session-stream fixtures：线格式喂入解析器得到 ready + 15 条 message + stats")
    func parserParsesSessionStreamFixture() throws {
        let frames = try Self.fixtureFrames("session-stream.json")
        #expect(frames.count == 17)

        var parser = SSELineParser()
        var events: [SSEEvent] = []
        for line in try Self.wire(frames) {
            if let event = parser.feed(line) {
                events.append(event)
            }
        }

        #expect(events.count == frames.count)
        #expect(events.first?.event == "ready")
        #expect(events.last?.event == "stats")
        // 会话流帧不带 id 行，且流从未出现过 id：lastEventID 恒为 nil。
        #expect(events.allSatisfy { $0.id == nil })

        let ready = try JSONDecoder().decode(
            StreamReadyEvent.self,
            from: Data(try #require(events.first).data.utf8)
        )
        #expect(ready.resumeSeq == 0)
        #expect(ready.protocolVersion == 2)
        #expect(ready.capabilities?.sync?.resync == true)
        #expect(ready.capabilities?.sync?.catchupIntegrity == true)

        let messageSeqs: [Int] = try events.compactMap { event -> Int? in
            guard event.event == "message" else { return nil }
            let envelope = try JSONDecoder().decode(SessionEventEnvelope.self, from: Data(event.data.utf8))
            return envelope.seq
        }
        #expect(messageSeqs == Array(1...15))
    }

    @Test("host-events fixtures：id 行驱动事件 id 与解码")
    func parserParsesHostEventsFixture() throws {
        let frames = try Self.fixtureFrames("host-events.json")
        var parser = SSELineParser()
        var events: [SSEEvent] = []
        for line in try Self.wire(frames) {
            if let event = parser.feed(line) {
                events.append(event)
            }
        }

        #expect(events.count == 2)
        #expect(events.map(\.id) == ["1", "2"])
        #expect(events.map(\.event) == ["session/state", "session/state"])
        let payloads = try events.map { event -> HostSessionStateEvent in
            try JSONDecoder().decode(HostSessionStateEvent.self, from: Data(event.data.utf8))
        }
        #expect(payloads.map(\.sessionId) == ["sess-demo-login", "sess-demo-refactor"])
        #expect(payloads.map(\.state) == [.awaitingApproval, .running])
        #expect(payloads.map(\.origin) == [.user, .user])
    }

    @Test("解析器：SSE 规范细节（多行 data、注释、id 保留与清除、retry、空帧）")
    func parserSpecDetails() {
        // 多行 data 拼接。
        var parser = SSELineParser()
        var events = ["data: a", "data: b", ""].compactMap { parser.feed($0) }
        #expect(events.count == 1)
        #expect(events[0].data == "a\nb")
        #expect(events[0].event == nil)
        #expect(events[0].id == nil)

        // 注释行与未知字段忽略；冒号后空格可有可无。
        parser = SSELineParser()
        events = [": keep-alive", "event: message", "x-unknown: 1", "data:hi", ""].compactMap { parser.feed($0) }
        #expect(events.count == 1)
        #expect(events[0].event == "message")
        #expect(events[0].data == "hi")

        // 没带 id 的帧保留上一次的 id；空 id 清除。
        parser = SSELineParser()
        events = ["id: 7", "data: a", "", "data: b", "", "id:", "data: c", ""].compactMap { parser.feed($0) }
        #expect(events.map(\.id) == ["7", "7", nil])
        #expect(events.map(\.data) == ["a", "b", "c"])

        // retry 毫秒数；非法值忽略。
        parser = SSELineParser()
        events = ["retry: 2500", "data: x", ""].compactMap { parser.feed($0) }
        #expect(events.count == 1)
        #expect(events[0].retry == 2500)
        parser = SSELineParser()
        events = ["retry: soon", "data: x", ""].compactMap { parser.feed($0) }
        #expect(events.count == 1)
        #expect(events[0].retry == nil)

        // 空帧不派发；无 data 的帧按规范清空缓冲，不影响下一帧。
        parser = SSELineParser()
        events = ["", "event: orphan", "id: 9", "", "data: after", ""].compactMap { parser.feed($0) }
        #expect(events.count == 1)
        #expect(events[0].data == "after")
        #expect(events[0].event == nil)

        // 心跳帧（注释/data 之外的正常帧）与 CR 结尾。
        parser = SSELineParser()
        events = ["event: heartbeat", "data: {}", "", "data: x\r", ""].compactMap { parser.feed($0) }
        #expect(events.count == 2)
        #expect(events[0].event == "heartbeat")
        #expect(events[1].data == "x")
    }

    @Test("行切分：保留空行（AsyncBytes.lines 会吞掉空行），CRLF / LF / CR 都是行尾，UTF-8 不被切坏")
    func lineSplitterKeepsBlankLines() {
        var splitter = SSELineSplitter()
        let wire = "data: a\n\ndata: 中文\r\n\r\n: keepalive\rdata: c\n\ndata: tail"
        var lines = Array(wire.utf8).compactMap { splitter.push($0) }
        if let rest = splitter.finish() {
            lines.append(rest)
        }
        #expect(lines == ["data: a", "", "data: 中文", "", ": keepalive", "data: c", "", "data: tail"])

        var parser = SSELineParser()
        let events = lines.compactMap { parser.feed($0) }
        #expect(events.map(\.data) == ["a", "中文", "c"])
    }

    @Test("默认传输：URLSessionSSETransport 读真实 AsyncBytes（本地文件）能分出帧")
    func urlSessionTransportSplitsFramesFromRealBytes() async throws {
        let file = FileManager.default.temporaryDirectory
            .appendingPathComponent("sse-\(UUID().uuidString).txt")
        try Data("id: 1\nevent: session/state\ndata: {}\n\n: keepalive\n\nid: 2\ndata: x\r\n\r\n".utf8)
            .write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }

        let connection = try await URLSessionSSETransport(session: .shared).open(URLRequest(url: file))
        var parser = SSELineParser()
        var events: [SSEEvent] = []
        for try await line in connection.lines {
            if let event = parser.feed(line) {
                events.append(event)
            }
        }
        #expect(events.map(\.id) == ["1", "2"])
        #expect(events.map(\.event) == ["session/state", nil])
        #expect(events.map(\.data) == ["{}", "x"])
    }

    // MARK: - SSEClient（fixtures 端到端）

    @Test("客户端：session-stream fixtures 端到端，按序收到 ready、15 条 message、stats")
    func clientDeliversSessionStreamFixtureInOrder() async throws {
        let frames = try Self.fixtureFrames("session-stream.json")
        let transport = ScriptedSSETransport(scripts: [.lines(try Self.wire(frames))])
        let cursors = Recorder<Int>()
        let client = SSEClient(
            transport: transport,
            makeRequest: { committed in
                cursors.append(committed)
                return URLRequest(url: Self.streamURL(committed))
            },
            timing: Self.fastTiming
        )
        let collector = OutputCollector()
        collector.start(client)

        await client.start()
        let statsArrived = await waitUntil {
            let events = await collector.events()
            return events.last?.event == "stats"
        }
        #expect(statsArrived)
        await client.stop()

        let events = await collector.events()
        #expect(events.count == frames.count)
        let ready = try JSONDecoder().decode(
            StreamReadyEvent.self,
            from: Data(try #require(events.first).data.utf8)
        )
        #expect(ready.resumeSeq == 0)
        // 会话流无 id 行：接收游标由上层 recordReceived 驱动，客户端不自推。
        let cursor = await client.cursorNow()
        #expect(cursor.received == 0)
        #expect(cursor.committed == 0)
        #expect(cursors.snapshot == [0])
    }

    @Test("客户端：host-events fixtures 端到端，id 自动进接收游标，commit 推进已提交游标")
    func clientAutoTracksReceivedFromEventID() async throws {
        let frames = try Self.fixtureFrames("host-events.json")
        let transport = ScriptedSSETransport(scripts: [.lines(try Self.wire(frames))])
        let client = SSEClient(
            transport: transport,
            makeRequest: { _ in URLRequest(url: Self.eventsURL) },
            timing: Self.fastTiming
        )
        let collector = OutputCollector()
        collector.start(client)

        await client.start()
        let bothArrived = await waitUntil {
            let events = await collector.events()
            return events.count == 2
        }
        #expect(bothArrived)

        var cursor = await client.cursorNow()
        #expect(cursor.received == 2)
        #expect(cursor.committed == 0)

        await client.commit(2)
        cursor = await client.cursorNow()
        #expect(cursor.committed == 2)
        #expect(cursor.resumeValue == 2)

        await client.stop()
        let terminated = await waitUntil { await collector.hasTerminated() }
        #expect(terminated)
    }

    // MARK: - 续传（重连用已提交游标）

    @Test("续传：主机事件流重连请求带 Last-Event-ID=已提交游标（非接收游标）")
    func hostEventsResumeUsesCommittedCursorHeader() async {
        let proceed = LockBox<Bool>(false)
        let transport = ScriptedSSETransport(scripts: [
            Script(status: 200) { continuation in
                for line in Self.hostEventLines(ids: [5, 6, 7]) {
                    continuation.yield(line)
                }
                await proceed.waitUntilTrue()
                continuation.finish()
            },
            .hold(),
        ])
        let cursors = Recorder<Int>()
        let client = SSEClient(
            transport: transport,
            makeRequest: { committed in
                cursors.append(committed)
                var request = URLRequest(url: Self.eventsURL)
                if committed > 0 {
                    request.setValue(String(committed), forHTTPHeaderField: "Last-Event-ID")
                }
                return request
            },
            timing: Self.fastTiming
        )
        let collector = OutputCollector()
        collector.start(client)

        await client.start()
        let received = await waitUntil { await client.cursorNow().received == 7 }
        #expect(received)
        // 只提交到 6；7 已接收未提交。
        await client.commit(6)
        proceed.set(true)

        let reconnected = await waitUntil { transport.openedRequests.count == 2 }
        #expect(reconnected)
        await client.stop()

        #expect(cursors.snapshot == [0, 6])
        let lastEventID = transport.openedRequests.snapshot.last?.value(forHTTPHeaderField: "Last-Event-ID")
        #expect(lastEventID == "6")
    }

    @Test("续传：会话流重连请求带 afterSeq=已提交游标")
    func sessionStreamResumeUsesAfterSeqQuery() async {
        let proceed = LockBox<Bool>(false)
        let transport = ScriptedSSETransport(scripts: [
            Script(status: 200) { continuation in
                for line in Self.sessionMessageLines(seqs: [1, 2]) {
                    continuation.yield(line)
                }
                await proceed.waitUntilTrue()
                continuation.finish()
            },
            .hold(),
        ])
        let cursors = Recorder<Int>()
        let client = SSEClient(
            transport: transport,
            makeRequest: { committed in
                cursors.append(committed)
                return URLRequest(url: Self.streamURL(committed))
            },
            timing: Self.fastTiming
        )
        let collector = OutputCollector()
        collector.start(client)

        await client.start()
        // 会话流 seq 在 data 里：上层解析后 recordReceived + commit。
        await client.recordReceived(2)
        await client.commit(2)
        proceed.set(true)

        let reconnected = await waitUntil { transport.openedRequests.count == 2 }
        #expect(reconnected)
        await client.stop()

        #expect(cursors.snapshot == [0, 2])
        #expect(
            transport.openedRequests.snapshot.last?.url?.query == "afterSeq=2&caps=sync2,multiQuestion,requestState"
        )
    }

    @Test("缺口：不连续 seq 照常转发，游标不越过缺口")
    func gapDoesNotAdvanceCommittedCursor() async {
        let proceed = LockBox<Bool>(false)
        let transport = ScriptedSSETransport(scripts: [
            Script(status: 200) { continuation in
                for line in Self.hostEventLines(ids: [1, 2, 4]) {
                    continuation.yield(line)
                }
                await proceed.waitUntilTrue()
                continuation.finish()
            },
            .hold(),
        ])
        let cursors = Recorder<Int>()
        let client = SSEClient(
            transport: transport,
            makeRequest: { committed in
                cursors.append(committed)
                var request = URLRequest(url: Self.eventsURL)
                if committed > 0 {
                    request.setValue(String(committed), forHTTPHeaderField: "Last-Event-ID")
                }
                return request
            },
            timing: Self.fastTiming
        )
        let collector = OutputCollector()
        collector.start(client)

        await client.start()
        let received = await waitUntil { await client.cursorNow().received == 4 }
        #expect(received)
        // 合同：只提交可证连续的部分（2），不把尾部 4 当作已证补发。
        await client.commit(2)
        proceed.set(true)

        let reconnected = await waitUntil { transport.openedRequests.count == 2 }
        #expect(reconnected)
        await client.stop()

        let events = await collector.events()
        #expect(events.count == 3)
        #expect(events.map(\.id) == ["1", "2", "4"])
        let cursor = await client.cursorNow()
        #expect(cursor.received == 4)
        #expect(cursor.committed == 2)
        #expect(cursors.snapshot == [0, 2])
        #expect(transport.openedRequests.snapshot.last?.value(forHTTPHeaderField: "Last-Event-ID") == "2")
    }

    // MARK: - resync-required

    @Test("resync-required：丢弃未提交增量、不再自动重连，快照游标给出后从该游标继续")
    func resyncRequiredParksUntilSnapshotCursor() async throws {
        let resyncFrameLines = try Array(Self.wire(try Self.fixtureFrames("session-stream-resync.json")).suffix(4))
        let committedFlag = LockBox<Bool>(false)
        let transport = ScriptedSSETransport(scripts: [
            Script(status: 200) { continuation in
                for line in Self.hostEventLines(ids: [3, 4]) {
                    continuation.yield(line)
                }
                await committedFlag.waitUntilTrue()
                for line in resyncFrameLines {
                    continuation.yield(line)
                }
                continuation.finish()
            }
        ])
        let cursors = Recorder<Int>()
        let client = SSEClient(
            transport: transport,
            makeRequest: { committed in
                cursors.append(committed)
                return URLRequest(url: Self.streamURL(committed, sessionId: "sess-demo-stopped"))
            },
            timing: Self.fastTiming
        )
        let collector = OutputCollector()
        collector.start(client)

        await client.start()
        let received = await waitUntil { await client.cursorNow().received == 4 }
        #expect(received)
        await client.commit(3)
        committedFlag.set(true)

        let resynced = await waitUntil {
            await collector.box.snapshot.contains { output in
                if case .resyncRequired = output { return true }
                return false
            }
        }
        #expect(resynced)

        // 丢弃未提交增量：接收游标回到已提交游标。
        var cursor = await client.cursorNow()
        #expect(cursor.received == 3)
        #expect(cursor.committed == 3)

        // 等待快照期间不自动重连。
        try await Task.sleep(for: .milliseconds(150))
        #expect(transport.openedRequests.count == 1)

        await client.resumeFromSnapshot(5)
        let reconnected = await waitUntil { transport.openedRequests.count == 2 }
        #expect(reconnected)
        await client.stop()

        // resync-required 帧按合同五字段下发。
        let resyncOutputs = await collector.box.snapshot.compactMap { output -> ResyncRequiredEvent? in
            if case .resyncRequired(let event) = output {
                return try? JSONDecoder().decode(ResyncRequiredEvent.self, from: Data(event.data.utf8))
            }
            return nil
        }
        #expect(resyncOutputs.count == 1)
        #expect(resyncOutputs.first?.sessionId == "sess-demo-stopped")
        #expect(resyncOutputs.first?.reason == "discontiguous")
        #expect(resyncOutputs.first?.nextCursor == 0)

        #expect(cursors.snapshot == [0, 5])
        #expect(transport.openedRequests.snapshot.last?.url?.query?.contains("afterSeq=5") == true)
        cursor = await client.cursorNow()
        #expect(cursor == SSECursor(received: 5, committed: 5))
    }

    // MARK: - 心跳超时 / 401 / 退避

    @Test("心跳超时：无行到达判定断线并按退避重连")
    func heartbeatTimeoutTriggersReconnect() async {
        let transport = ScriptedSSETransport(scripts: [.hold()])
        let client = SSEClient(
            transport: transport,
            makeRequest: { _ in URLRequest(url: Self.eventsURL) },
            timing: Self.fastTiming
        )
        let collector = OutputCollector()
        collector.start(client)

        await client.start()
        let retried = await waitUntil {
            await collector.box.snapshot.contains { output in
                if case .retryScheduled(_, let reason) = output { return reason == .heartbeatTimeout }
                return false
            }
        }
        #expect(retried)
        let reconnected = await waitUntil { transport.openedRequests.count >= 2 }
        #expect(reconnected)
        await client.stop()

        #expect(await collector.box.snapshot.contains(.retryScheduled(delayMs: 20, reason: .heartbeatTimeout)))
    }

    @Test("重连退避使用注入的 ±20% 随机源")
    func injectedJitterChangesScheduledDelay() async {
        let transport = ScriptedSSETransport(scripts: [.hold()])
        let client = SSEClient(
            transport: transport,
            makeRequest: { _ in URLRequest(url: Self.eventsURL) },
            timing: SSEClient.Timing(
                heartbeatTimeout: .milliseconds(300),
                initialBackoff: .milliseconds(20),
                maxBackoff: .milliseconds(120),
                jitterFraction: 0.2,
                jitterSample: { 0 }
            )
        )
        let collector = OutputCollector()
        collector.start(client)

        await client.start()
        let retried = await waitUntil {
            await collector.box.snapshot.contains { output in
                if case .retryScheduled(_, let reason) = output { return reason == .heartbeatTimeout }
                return false
            }
        }
        #expect(retried)
        await client.stop()

        #expect(await collector.box.snapshot.contains(.retryScheduled(delayMs: 16, reason: .heartbeatTimeout)))
    }

    @Test("401：终止且不再重连")
    func unauthorizedTerminatesWithoutReconnect() async {
        let transport = ScriptedSSETransport(scripts: [.hold(status: 401)])
        let client = SSEClient(
            transport: transport,
            makeRequest: { _ in URLRequest(url: Self.eventsURL) },
            timing: Self.fastTiming
        )
        let collector = OutputCollector()
        collector.start(client)

        await client.start()
        let terminated = await waitUntil { await collector.hasTerminated() }
        #expect(terminated)
        await client.stop()

        // 不发 .connected，不做任何重连。
        #expect(await collector.box.snapshot == [.terminated(.unauthorized)])
        #expect(transport.openedRequests.count == 1)
        try? await Task.sleep(for: .milliseconds(120))
        #expect(transport.openedRequests.count == 1)
    }

    @Test("退避序列：指数增长、基础档封顶，抖动可注入")
    func backoffSequenceIsExponentialAndCapped() {
        let delays = (1...8).map { SSEClient.backoffDelay(failures: $0, initial: .seconds(1), cap: .seconds(30)) }
        #expect(
            delays == [
                .seconds(1), .seconds(2), .seconds(4), .seconds(8), .seconds(16), .seconds(30), .seconds(30),
                .seconds(30),
            ])
        #expect(delays.allSatisfy { $0 <= .seconds(30) })

        // 毫秒级注入（测试用）：同样指数增长、封顶。
        let fast = (1...5).map {
            SSEClient.backoffDelay(failures: $0, initial: .milliseconds(20), cap: .milliseconds(120))
        }
        #expect(
            fast == [.milliseconds(20), .milliseconds(40), .milliseconds(80), .milliseconds(120), .milliseconds(120)])

        // ±20%：注入 0 / 0.5 / 1，不依赖系统随机。抖动在封顶之后，上界可以超过 cap。
        let low = SSEClient.backoffDelay(
            failures: 1, initial: .seconds(1), cap: .seconds(30), jitterFraction: 0.2, unitSample: 0)
        let mid = SSEClient.backoffDelay(
            failures: 1, initial: .seconds(1), cap: .seconds(30), jitterFraction: 0.2, unitSample: 0.5)
        let high = SSEClient.backoffDelay(
            failures: 1, initial: .seconds(1), cap: .seconds(30), jitterFraction: 0.2, unitSample: 1)
        #expect(low == .milliseconds(800))
        #expect(mid == .seconds(1))
        #expect(high == .milliseconds(1_200))
        let cappedLow = SSEClient.backoffDelay(
            failures: 8, initial: .seconds(1), cap: .seconds(30), jitterFraction: 0.2, unitSample: 0)
        let cappedHigh = SSEClient.backoffDelay(
            failures: 8, initial: .seconds(1), cap: .seconds(30), jitterFraction: 0.2, unitSample: 1)
        #expect(cappedLow == .seconds(24))
        #expect(cappedHigh == .seconds(36))
        // 越界采样夹到 [0, 1]；负比例不抖动；比例超过 1 按 1 处理。
        let clampedLow = SSEClient.backoffDelay(
            failures: 1, initial: .seconds(1), cap: .seconds(30), jitterFraction: 0.2, unitSample: -4)
        let clampedHigh = SSEClient.backoffDelay(
            failures: 1, initial: .seconds(1), cap: .seconds(30), jitterFraction: 0.2, unitSample: 9)
        #expect(clampedLow == .milliseconds(800))
        #expect(clampedHigh == .milliseconds(1_200))
        let noJitter = SSEClient.backoffDelay(
            failures: 2, initial: .seconds(1), cap: .seconds(30), jitterFraction: -1, unitSample: 0)
        #expect(noJitter == .seconds(2))
        let fullSwing = SSEClient.backoffDelay(
            failures: 2, initial: .seconds(1), cap: .seconds(30), jitterFraction: 3, unitSample: 0)
        #expect(fullSwing == .zero)
    }

    // MARK: - 前台门控

    @Test("前台门控：background 主动断开，active 用已提交游标重连，inactive 不动连接")
    func foregroundGateDisconnectsAndReconnects() async {
        let gate = ForegroundGate(phase: .background)
        let transport = ScriptedSSETransport(scripts: [.hold(Self.hostEventLines(ids: [3]))])
        let cursors = Recorder<Int>()
        let client = SSEClient(
            transport: transport,
            makeRequest: { committed in
                cursors.append(committed)
                var request = URLRequest(url: Self.eventsURL)
                if committed > 0 {
                    request.setValue(String(committed), forHTTPHeaderField: "Last-Event-ID")
                }
                return request
            },
            timing: Self.fastTiming,
            phase: .background
        )
        await client.follow(gate)
        let collector = OutputCollector()
        collector.start(client)

        // 后台启动：不建立连接。
        await client.start()
        try? await Task.sleep(for: .milliseconds(100))
        #expect(transport.openedRequests.count == 0)

        // 回到前台：连接。
        await gate.update(.active)
        let connected = await waitUntil { transport.openedRequests.count == 1 }
        #expect(connected)
        let received = await waitUntil { await client.cursorNow().received == 3 }
        #expect(received)
        await client.commit(3)

        // inactive（来电、控制中心等）：保持连接。
        await gate.update(.inactive)
        try? await Task.sleep(for: .milliseconds(80))
        #expect(transport.openedRequests.count == 1)
        #expect(transport.cancelledCount.current == 0)

        // 进后台：主动断开。
        await gate.update(.background)
        let cancelled = await waitUntil { transport.cancelledCount.current >= 1 }
        #expect(cancelled)

        // 回前台：用已提交游标重连。
        await gate.update(.active)
        let reconnected = await waitUntil { transport.openedRequests.count == 2 }
        #expect(reconnected)
        await client.stop()

        #expect(transport.openedRequests.snapshot.last?.value(forHTTPHeaderField: "Last-Event-ID") == "3")
        #expect(cursors.snapshot == [0, 3])
    }

    @Test("ForegroundGate：订阅即得当前阶段，只在变化时广播")
    func foregroundGateBroadcastsChanges() async {
        let gate = ForegroundGate(phase: .active)
        let phases = Recorder<AppPhase>()
        let task = Task {
            for await phase in gate.changes() {
                phases.append(phase)
            }
        }

        let subscribed = await waitUntil { !phases.snapshot.isEmpty }
        #expect(subscribed)
        #expect(phases.snapshot == [.active])

        await gate.update(.background)
        let changed = await waitUntil { phases.snapshot == [.active, .background] }
        #expect(changed)

        // 相同阶段不重复广播。
        await gate.update(.background)
        try? await Task.sleep(for: .milliseconds(80))
        #expect(phases.snapshot == [.active, .background])
        #expect(gate.phase == .background)

        task.cancel()
    }

    // MARK: - I7.3 发送中切后台

    @Test("只有后台且发送未完成时才申请短后台时间")
    func backgroundSendDecisionCoversOnlyInFlightWrite() {
        #expect(BackgroundSendDecision.entering(phase: .active, sendInFlight: true) == .idle)
        #expect(BackgroundSendDecision.entering(phase: .inactive, sendInFlight: true) == .idle)
        #expect(BackgroundSendDecision.entering(phase: .background, sendInFlight: false) == .idle)
        #expect(BackgroundSendDecision.entering(phase: .background, sendInFlight: true) == .begin)
        #expect(BackgroundSendDecision.finishing() == .end)
    }

    @Test("一次在途发送只申请一个后台任务，结束后关闭")
    func backgroundSendCoverBeginsOnceAndEnds() async {
        let tasks = RecordingBackgroundTasks()
        let cover = BackgroundSendCover(tasks: tasks)
        #expect(cover.coverIfNeeded(phase: .background) == .idle)
        #expect(tasks.beganNames.isEmpty)

        cover.beginSend(phase: .active)
        #expect(cover.coverIfNeeded(phase: .active) == .idle)
        #expect(cover.coverIfNeeded(phase: .background) == .begin)
        #expect(cover.coverIfNeeded(phase: .background) == .idle)
        #expect(tasks.beganNames == [BackgroundSendCover.taskName])
        #expect(tasks.openCount == 1)

        cover.beginSend(phase: .background)
        #expect(tasks.beganNames.count == 1)
        #expect(tasks.openCount == 1)
        cover.finishSend()
        #expect(tasks.openCount == 1)
        cover.finishSend()
        #expect(tasks.openCount == 0)
        #expect(tasks.endedTokens.count == 1)
        #expect(cover.hasOpenTask == false)
    }
}
