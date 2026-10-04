import DLCore
import DLModels
import DLSecurity
import Foundation
import Testing

@testable import DeepLinks

@MainActor @Suite struct ConversationFlowTests {
    @Test func snapshotThenHistoryReplacesWholePage() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
            UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let box = TranscriptSnapshotBox(keys: InMemorySecureStore(), directory: directory)
        let pending = HistoryMessage(
            id: "ap", role: "approval", kind: .role("approval"), text: "bash deploy", requestStatus: .pending)
        try box.write(
            hostID: "host", sessionID: "session",
            plaintext: try TranscriptSnapshotCoding.encode(
                TranscriptSnapshotRecord(
                    messages: [pending], stats: nil, maxSeq: 3, title: "发布说明", workspace: "/work/app", running: false,
                    step: nil)))
        let history = HistoryResponse(
            messages: [HistoryMessage(id: "u", role: "user", kind: .user, text: "继续")], maxSeq: 8)
        let service = GatedHistory(history)
        let model = ConversationModel(
            hostID: "host", sessionID: "session", seed: ConversationSeed(title: "发布说明", workspace: "/work/app"),
            service: service, box: box, autostart: true)
        let task = Task { await model.start() }
        var sawUnconfirmed = false
        for _ in 0..<50 {
            if model.rows == [.unconfirmed(id: "ap")] {
                sawUnconfirmed = true
                break
            }
            try await Task.sleep(for: .milliseconds(20))
        }
        #expect(sawUnconfirmed)
        #expect(model.confirmingSnapshot)
        #expect(!model.approvalsSubmittable)
        await service.release()
        await task.value
        #expect(!model.confirmingSnapshot)
        #expect(model.messages == history.messages)
        #expect(!model.rows.contains { if case .unconfirmed = $0 { true } else { false } })
        let savedPlaintext = try box.read(hostID: "host", sessionID: "session")
        let saved = try TranscriptSnapshotCoding.decode(try #require(savedPlaintext))
        #expect(saved.messages == history.messages)
        #expect(saved.maxSeq == 8)
    }

    @Test func unauthorizedKeepsSnapshot() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
            UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let box = TranscriptSnapshotBox(keys: InMemorySecureStore(), directory: directory)
        let message = HistoryMessage(id: "u", role: "user", kind: .user, text: "还在")
        try box.write(
            hostID: "host", sessionID: "session",
            plaintext: try TranscriptSnapshotCoding.encode(
                TranscriptSnapshotRecord(
                    messages: [message], stats: nil, maxSeq: 1, title: "发布说明", workspace: "/work/app", running: false,
                    step: nil)))
        let model = ConversationModel(
            hostID: "host", sessionID: "session", service: FailingHistory(), box: box, autostart: true)
        await model.start()
        #expect(model.loadFailed)
        #expect(model.messages == [message])
        #expect(!model.approvalsSubmittable)
    }

    @Test func actionsDoNotSend() async {
        let model = ConversationModel(
            hostID: "host", sessionID: "session",
            service: IdleConversationService(),
            box: TranscriptSnapshotBox(keys: InMemorySecureStore(), directory: URL(fileURLWithPath: "/tmp")),
            prepared: PreparedTranscript(messages: [], running: false), autostart: false)
        model.suggest("继续")
        model.regenerate("再写一版")
        model.viewChanges(seq: 4)
        model.noteDiff()
        model.copyAssistant("正文")
        await model.loadImage("http://cdn.example.com/a.png")
        #expect(model.suggestedDraft == "继续")
        #expect(model.regenerateText == "再写一版")
        #expect(model.changesRequest == 4)
        #expect(model.diffRequested)
        #expect(model.copiedText == "正文")
        #expect(model.images["http://cdn.example.com/a.png"] == .blocked)
        #expect(!model.approvalsSubmittable)
    }

    @Test func subtitleAndActivity() {
        let zh = ConversationCopy(locale: Locale(identifier: "zh-Hans"))
        let en = ConversationCopy(locale: Locale(identifier: "en"))
        #expect(en.text(.running) == "Running")
        #expect(en.subtitle(workspace: "app", phase: .running(step: 3)) == "app · Running · Step 3")
        #expect(zh.text(.unconfirmed) == "状态待确认" || zh.text(.unconfirmed) == "Status not confirmed yet")
        let line = en.activity(ActivitySummary(parts: [.commands(2), .reads(1)], more: 1))
        #expect(line == "Ran 2 commands · Read 1 files +1")
    }
}

private struct IdleConversationService: ConversationServing {}

private actor FailingHistory: ConversationServing {
    func history(sessionID: String) async throws -> HistoryResponse {
        _ = sessionID
        throw ConversationServiceError.unauthorized
    }
}

private actor GatedHistory: ConversationServing {
    private let response: HistoryResponse
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(_ response: HistoryResponse) {
        self.response = response
    }

    func history(sessionID: String) async throws -> HistoryResponse {
        _ = sessionID
        await withCheckedContinuation { waiters.append($0) }
        return response
    }

    func release() {
        let pending = waiters
        waiters.removeAll()
        for waiter in pending { waiter.resume() }
    }
}
