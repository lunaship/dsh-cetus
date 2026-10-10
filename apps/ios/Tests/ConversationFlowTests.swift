import DLCore
import DLModels
import DLSecurity
import Foundation
import Testing

@testable import Cetus

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

    @Test func restartSnapshotRecoversEventsMissedWhileOffline() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
            UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let box = TranscriptSnapshotBox(keys: InMemorySecureStore(), directory: directory)
        let old = HistoryMessage(id: "old", role: "user", kind: .user, text: "旧消息")
        try box.write(
            hostID: "host", sessionID: "session",
            plaintext: try TranscriptSnapshotCoding.encode(
                TranscriptSnapshotRecord(
                    messages: [old], stats: nil, maxSeq: 4, title: "发布说明", workspace: "/work/app", running: false,
                    step: nil)))
        let recovered = HistoryMessage(id: "new", role: "assistant", kind: .role("assistant"), text: "重启期间产生")
        let service = RecordingRestartService(
            HistoryResponse(messages: [old, recovered], maxSeq: 9))
        let model = ConversationModel(
            hostID: "host", sessionID: "session", seed: ConversationSeed(title: "发布说明", workspace: "/work/app"),
            service: service, box: box, autostart: true)
        await model.start()
        #expect(model.messages.map { $0.id } == ["old", "new"])
        #expect(await service.openedAfter == [9])
        #expect(!model.loadFailed)
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

    @Test func fileSourceSendsImageAndRejectsOtherFiles() async throws {
        #expect(!promptImageCroppingAvailable)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
            UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let service = RecordingPromptService()
        let model = ConversationModel(
            hostID: "host", sessionID: "session",
            service: service,
            box: TranscriptSnapshotBox(keys: InMemorySecureStore(), directory: directory),
            prepared: PreparedTranscript(messages: [], running: false), autostart: false)
        let png = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00])
        let image = try #require(
            promptImage(from: classifyPromptAttachment(bytes: png, declaredMediaType: "image/png", existingCount: 0)))
        try await model.serviceSend("", images: [image])
        let sent = await service.sent
        #expect(sent.count == 1)
        #expect(sent[0].text.isEmpty)
        #expect(sent[0].images == [image])

        let text = Data("notes".utf8)
        #expect(
            classifyPromptAttachment(bytes: text, declaredMediaType: "text/plain", existingCount: 0)
                == .rejected(.unsupported))
        let encoded = try JSONDecoder().decode(
            ProbePrompt.self, from: encodePromptRequest(text: "hello", mode: "queue", images: []))
        #expect(encoded.images == nil)
        #expect(await service.sent.count == 1)
    }

    @Test func disconnectedWritesAttemptOnceAndKeepDraft() async {
        let service = FailingPromptService()
        let model = ConversationModel(
            hostID: "host", sessionID: "session",
            service: service,
            box: TranscriptSnapshotBox(keys: InMemorySecureStore(), directory: URL(fileURLWithPath: "/tmp")),
            prepared: PreparedTranscript(messages: [], running: false), autostart: false)
        await model.retryConnection()
        #expect(model.loadFailed)
        await #expect(throws: ConversationServiceError.offline) {
            try await model.serviceSend("继续", images: [])
        }
        #expect(await service.attempts == 1)
        await model.retryConnection()
        #expect(await service.attempts == 1)
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

private actor FailingPromptService: ConversationServing {
    private(set) var attempts = 0

    func sendPrompt(sessionID: String, text: String, images: [PromptImage]) async throws {
        _ = (sessionID, text, images)
        attempts += 1
        throw ConversationServiceError.offline
    }
}

private actor RecordingPromptService: ConversationServing {
    private(set) var sent: [(text: String, images: [PromptImage])] = []

    func sendPrompt(sessionID: String, text: String, images: [PromptImage]) async throws {
        _ = sessionID
        sent.append((text, images))
    }
}

private struct ProbePrompt: Decodable {
    var text: String
    var mode: String
    var images: [PromptImage]?
}

private func promptImage(from decision: PromptAttachmentDecision) -> PromptImage? {
    if case .image(let image) = decision { return image }
    return nil
}

private actor RecordingRestartService: ConversationServing {
    private let response: HistoryResponse
    private(set) var openedAfter: [Int] = []

    init(_ response: HistoryResponse) {
        self.response = response
    }

    func history(sessionID: String, beforeSeq: Int?) async throws -> HistoryResponse {
        _ = (sessionID, beforeSeq)
        return response
    }

    func open(sessionID: String, afterSeq: Int) async throws -> AsyncStream<ConversationSignal> {
        _ = sessionID
        openedAfter.append(afterSeq)
        return AsyncStream { $0.finish() }
    }
}

private actor FailingHistory: ConversationServing {
    func history(sessionID: String, beforeSeq: Int?) async throws -> HistoryResponse {
        _ = (sessionID, beforeSeq)
        throw ConversationServiceError.unauthorized
    }
}

private actor GatedHistory: ConversationServing {
    private let response: HistoryResponse
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(_ response: HistoryResponse) {
        self.response = response
    }

    func history(sessionID: String, beforeSeq: Int?) async throws -> HistoryResponse {
        _ = (sessionID, beforeSeq)
        await withCheckedContinuation { waiters.append($0) }
        return response
    }

    func release() {
        let pending = waiters
        waiters.removeAll()
        for waiter in pending { waiter.resume() }
    }
}

private actor StubModelsService: ConversationServing {
    let response: SessionModelsResponse
    init(_ response: SessionModelsResponse) { self.response = response }
    func models(sessionID: String) async throws -> SessionModelsResponse {
        _ = sessionID
        return response
    }
}

@MainActor @Suite struct CurrentModelTests {
    private func makeModel(_ response: SessionModelsResponse) throws -> ConversationModel {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
            UUID().uuidString, isDirectory: true)
        let box = TranscriptSnapshotBox(keys: InMemorySecureStore(), directory: directory)
        return ConversationModel(
            hostID: "host", sessionID: "s-1", service: StubModelsService(response), box: box, autostart: false)
    }

    /// P1：能拿到 current.model 时返回 id 和 effort。
    @Test func currentModelReturned() async throws {
        let response = SessionModelsResponse(
            current: SessionModelCurrent(provider: "deepseek", model: "deepseek-chat", reasoningEffort: "high"))
        let model = try makeModel(response)
        let current = await model.serviceCurrentModel()
        #expect(current?.id == "deepseek-chat")
        #expect(current?.effort == "high")
    }

    /// P1：拿不到 current 时返回 nil，不填假值。
    @Test func currentModelNilWhenMissing() async throws {
        let model = try makeModel(SessionModelsResponse())
        #expect(await model.serviceCurrentModel() == nil)
    }

    /// P1：current.model 为空字符串时返回 nil。
    @Test func currentModelNilWhenEmpty() async throws {
        let response = SessionModelsResponse(current: SessionModelCurrent(model: ""))
        let model = try makeModel(response)
        #expect(await model.serviceCurrentModel() == nil)
    }
}
