import DLModels
import Foundation
import Testing

/// 合同测试（PLAN I3.2）：逐个解码 `testdata/mobile-contract/` 里由真实插件生成的响应样本，
/// 文件 ↔ 接口对照与占位串规则见同目录 README.md。解码失败、SSE 出现未知 event、
/// 枚举落到 `.unknown`，都说明 DLModels 与真实插件响应不符，要以真实响应为准改模型。
/// 对照表与目录 `.json` 集合不一致（插件新增 / 删除手机接口）同样视为失败。
struct MobileContractFixturesTests {
    private struct FixtureError: Error, CustomStringConvertible {
        let message: String
        var description: String { message }

        init(_ message: String) {
            self.message = message
        }
    }

    /// SSE 帧文件的单帧：`{event, id, data}`；`data` 原样留给按 `event` 分发。
    private struct RawFrame {
        var event: String
        var data: Data
    }

    private typealias FixtureDecoder = @Sendable (Data) throws -> Void

    // MARK: - 文件名 → 解码方式

    private static let decoders: [String: FixtureDecoder] = [
        "approval-submit.json": expect(RequestSubmitResponse.self),
        "balance.json": expect(BalanceResponse.self),
        "bootstrap.json": expect(BootstrapResponse.self),
        "cancel.json": expect(CancelResponse.self),
        "changes-diff.json": expect(ChangesDiffResponse.self),
        "changes.json": expect(ChangesSummaryResponse.self),
        "devices.json": expect(DevicesResponse.self),
        "diagnostics.json": expect(DiagnosticsReport.self),
        // 生成脚本从 file 响应头拼出的元信息快照（content-type / SHA-256 / 文件名），
        // 不是插件的 JSON 响应体，App 按响应头读取；这里只验证它是合法 JSON。
        "file-meta.json": expect(JSONValue.self),
        "history.json": expect(HistoryResponse.self),
        "host-events.json": hostEventFrames,
        "pair-pending.json": expect(PairResponse.self),
        "pair-same-name-409.json": expect(PairConflictResponse.self),
        "pair.json": expect(PairResponse.self),
        "preview-detections.json": expect(PreviewDetectionsResponse.self),
        "previews.json": expect(PreviewsResponse.self),
        "prompt.json": expect(PromptResponse.self),
        "providers.json": expect(ProvidersResponse.self),
        "question-submit.json": expect(RequestSubmitResponse.self),
        "queue-edit.json": expect(QueueActionResponse.self),
        "requests.json": expect(RequestsSnapshotResponse.self),
        "revoke.json": expect(RevokeResponse.self),
        "session-stream-resync.json": sessionStreamFrames,
        "session-stream.json": sessionStreamFrames,
        "sessions-search.json": expect(SessionSearchResponse.self),
        "sessions.json": expect(SessionListResponse.self),
        "tree.json": expect(TreeResponse.self),
        "workspaces-200.json": expect(WorkspaceCreateResponse.self),
        "workspaces-202.json": expect(WorkspacePendingApprovalResponse.self),
    ]

    private static let sortedFixtureNames: [String] = decoders.keys.sorted()

    @Test(arguments: MobileContractFixturesTests.sortedFixtureNames)
    func decodesWithTheMappedModel(_ fileName: String) throws {
        guard let decode = Self.decoders[fileName] else {
            Issue.record("\(fileName) 不在对照表里")
            return
        }
        do {
            try decode(try fileData(fileName))
        } catch {
            Issue.record("\(fileName) 解码失败：\(Self.describe(error))")
        }
    }

    @Test func fixtureDirectoryMatchesTheDecoderMap() throws {
        let onDisk = Set(try fixtureFileNames())
        let mapped = Set(Self.decoders.keys)
        let added = onDisk.subtracting(mapped).sorted()
        let removed = mapped.subtracting(onDisk).sorted()
        #expect(!onDisk.isEmpty, "没读到 fixtures，路径应是 <repo>/testdata/mobile-contract/")
        #expect(
            added.isEmpty && removed.isEmpty,
            "对照表与目录不一致：新增 \(added) 是插件新接口，需在 decoders 补解码；减少 \(removed) 需清掉表项"
        )
    }

    // MARK: - 关键语义

    @Test func bootstrapReportsRemoteDisabled() throws {
        let bootstrap = try decodeFile(BootstrapResponse.self, "bootstrap.json")
        #expect(bootstrap.remote == .disabled)
    }

    @Test func historyMessageKindsResolveAndCoverInjections() throws {
        let history = try decodeFile(HistoryResponse.self, "history.json")
        let messages = try #require(history.messages)
        #expect(!messages.isEmpty)
        #expect(messages.allSatisfy { $0.kind != nil }, "每条消息的 kind 都应能解析")
        #expect(messages.contains { $0.kind == .injection || $0.kind == .goalRound })
    }

    @Test func requestsCarryAPendingApproval() throws {
        let snapshot = try decodeFile(RequestsSnapshotResponse.self, "requests.json")
        #expect(snapshot.approvals?.contains { $0.status == .pending } == true)
    }

    /// 真实插件下发的枚举取值都必须落在已知 case：落到 `.unknown` 说明 DLModels 缺取值。
    @Test func realPluginEnumValuesAreAllKnown() throws {
        let history = try decodeFile(HistoryResponse.self, "history.json")
        #expect(history.goal?.phase == .active)
        #expect(history.goal?.ref?.revision == 3)
        #expect(history.goal?.roundsStarted == 2)
        #expect(history.stats?.contextPressure?.contextWindow == 1_048_576)
        #expect(history.stats?.estimatedCost?.source == .host)
        for item in history.queue ?? [] {
            if case .unknown(let raw)? = item.placement {
                Issue.record("queue placement 未知取值：\(raw)")
            }
        }
        for message in history.messages ?? [] {
            if case .unknown(let raw)? = message.requestStatus {
                Issue.record("requestStatus 未知取值：\(raw)")
            }
        }

        let sessions = try decodeFile(SessionListResponse.self, "sessions.json")
        for summary in sessions.sessions ?? [] {
            if case .unknown(let raw)? = summary.activity?.kind {
                Issue.record("activity kind 未知取值：\(raw)")
            }
        }

        let tree = try decodeFile(TreeResponse.self, "tree.json")
        for entry in tree.entries ?? [] {
            if case .unknown(let raw)? = entry.type {
                Issue.record("tree entry type 未知取值：\(raw)")
            }
        }

        let diff = try decodeFile(ChangesDiffResponse.self, "changes-diff.json")
        if case .unknown(let raw)? = diff.kind {
            Issue.record("diff kind 未知取值：\(raw)")
        }

        let balance = try decodeFile(BalanceResponse.self, "balance.json")
        #expect(balance.status == .ready)

        let providers = try decodeFile(ProvidersResponse.self, "providers.json")
        for row in providers.providers ?? [] {
            if case .unknown(let raw)? = row.kind {
                Issue.record("provider kind 未知取值：\(raw)")
            }
        }

        let diagnostics = try decodeFile(DiagnosticsReport.self, "diagnostics.json")
        for check in diagnostics.checks ?? [] {
            if case .unknown(let raw)? = check.status {
                Issue.record("diagnostics status 未知取值：\(raw)")
            }
        }

        let devices = try decodeFile(DevicesResponse.self, "devices.json")
        for row in devices.devices ?? [] {
            if case .unknown(let raw)? = row.via {
                Issue.record("device via 未知取值：\(raw)")
            }
            if case .unknown(let raw)? = row.status {
                Issue.record("device status 未知取值：\(raw)")
            }
        }

        for frame in try Self.frames(from: fileData("host-events.json")) {
            let event = try JSONDecoder().decode(HostSessionStateEvent.self, from: frame.data)
            if case .unknown(let raw)? = event.state {
                Issue.record("host session/state 未知取值：\(raw)")
            }
            if case .unknown(let raw)? = event.origin {
                Issue.record("host origin 未知取值：\(raw)")
            }
        }
    }

    // MARK: - SSE 帧分发

    /// 单会话 SSE（caps=sync2,multiQuestion,requestState）的 event → 帧类型；未知 event 视为漂移。
    private static let sessionStreamFrames: FixtureDecoder = { data in
        for frame in try frames(from: data) {
            switch frame.event {
            case "ready":
                try decode(StreamReadyEvent.self, frame.data)
            case "message":
                try decode(SessionEventEnvelope.self, frame.data)
            case "stats":
                try decode(StatsEvent.self, frame.data)
            case "resync-required":
                try decode(ResyncRequiredEvent.self, frame.data)
            case "question":
                try decode(QuestionRequestEvent.self, frame.data)
            case "question-resolved":
                try decode(QuestionResolvedEvent.self, frame.data)
            case let other:
                throw FixtureError("会话流出现未知 event：\(other)")
            }
        }
    }

    /// 主机事件流（`GET /dsh-link/mobile/events`）的 event → 帧类型；未知 event 视为漂移。
    private static let hostEventFrames: FixtureDecoder = { data in
        for frame in try frames(from: data) {
            switch frame.event {
            case "session/state":
                try decode(HostSessionStateEvent.self, frame.data)
            case "resync-required":
                try decode(HostEventResync.self, frame.data)
            case let other:
                throw FixtureError("主机事件流出现未知 event：\(other)")
            }
        }
    }

    // MARK: - 工具

    private static func expect<T: Decodable & Sendable>(_ type: T.Type) -> FixtureDecoder {
        { data in _ = try JSONDecoder().decode(type, from: data) }
    }

    private static func decode<T: Decodable>(_ type: T.Type, _ data: Data) throws {
        _ = try JSONDecoder().decode(type, from: data)
    }

    /// 帧的 `data` 经 JSONSerialization 原样再序列化：整数保持整数，不会变成浮点。
    private static func frames(from data: Data) throws -> [RawFrame] {
        let root = try JSONSerialization.jsonObject(with: data)
        guard let list = root as? [[String: Any]] else {
            throw FixtureError("SSE 帧文件应是 {event, id, data} 数组")
        }
        return try list.map { item in
            guard let event = item["event"] as? String, let payload = item["data"] else {
                let keys = item.keys.sorted().joined(separator: ",")
                throw FixtureError("SSE 帧缺少 event / data：\(keys)")
            }
            return RawFrame(
                event: event,
                data: try JSONSerialization.data(withJSONObject: payload, options: [.fragmentsAllowed])
            )
        }
    }

    private static func describe(_ error: any Error) -> String {
        guard let decoding = error as? DecodingError else { return String(describing: error) }
        func path(_ context: DecodingError.Context) -> String {
            context.codingPath.map(\.stringValue).joined(separator: ".")
        }
        switch decoding {
        case .dataCorrupted(let context):
            return "dataCorrupted \(path(context))：\(context.debugDescription)"
        case .keyNotFound(let key, let context):
            return "keyNotFound \(key.stringValue)（\(path(context))）：\(context.debugDescription)"
        case .typeMismatch(_, let context):
            return "typeMismatch \(path(context))：\(context.debugDescription)"
        case .valueNotFound(_, let context):
            return "valueNotFound \(path(context))：\(context.debugDescription)"
        @unknown default:
            return String(describing: decoding)
        }
    }

    private func decodeFile<T: Decodable>(_ type: T.Type, _ fileName: String) throws -> T {
        try JSONDecoder().decode(type, from: fileData(fileName))
    }

    private func fileData(_ fileName: String) throws -> Data {
        try Data(contentsOf: fixturesDirectory().appendingPathComponent(fileName))
    }

    private func fixtureFileNames() throws -> [String] {
        let names = try FileManager.default.contentsOfDirectory(
            at: fixturesDirectory(),
            includingPropertiesForKeys: nil
        )
        return names.map(\.lastPathComponent).filter { $0.hasSuffix(".json") }.sorted()
    }

    private func fixturesDirectory() -> URL {
        repoRoot().appendingPathComponent("testdata").appendingPathComponent("mobile-contract")
    }

    private func repoRoot() -> URL {
        // …/apps/ios/Tests/Contract/<file>.swift 逐级上溯 5 层到仓库根
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }
}
