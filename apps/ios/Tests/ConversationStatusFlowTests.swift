import DLCore
import DLModels
import DLNet
import DLSecurity
import Foundation
import Testing

@testable import DeepLinks

@MainActor @Suite struct ConversationStatusFlowTests {
    @Test func liveStreamDisconnectRecoveryAndGoalUpdates() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let service = StatusStreamService()
        let model = ConversationModel(
            hostID: "host", sessionID: "s", service: service,
            box: TranscriptSnapshotBox(keys: InMemorySecureStore(), directory: directory))
        await model.start()
        #expect(model.status.kind == .disconnected)
        await service.send(.connection(.connected))
        try await wait { model.status.kind == .goal }
        await service.send(.connection(.reconnecting))
        try await wait { model.status.kind == .disconnected }
        #expect(model.status.goal?.objective == "Fix")
        // No composer is implemented here. Offline status only describes SSE, not HTTP send policy.
        model.suggest("Still editable")
        #expect(model.suggestedDraft == "Still editable")
        await service.send(.connection(.connected))
        try await wait { model.status.kind == .goal }
        await service.send(.statusEvent(name: "stats", data: .object(["goal": .null, "todos": .array([])])))
        try await wait { model.status.kind == nil }
        await model.stop()
    }

    @Test func readyCapabilitiesGateReadOnlyStatusRefresh() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let service = StatusStreamService()
        let model = ConversationModel(
            hostID: "host", sessionID: "s", service: service,
            box: TranscriptSnapshotBox(keys: InMemorySecureStore(), directory: directory))
        await model.start()
        await service.send(.connection(.connected))
        await service.send(.statusEvent(name: "ready", data: .object(["capabilities": .object([:])])))
        try await wait { model.status.connection == .connected }
        #expect(await service.reads == 0)
        let data = try JSONEncoder().encode(
            StreamReadyEvent(
                capabilities: PluginCapabilities(
                    requests: RequestCapabilities(snapshot: true), preview: PreviewCapabilities(v: 1, detect: 1))))
        await service.send(.statusEvent(name: "ready", data: try JSONDecoder().decode(JSONValue.self, from: data)))
        try await wait { model.status.pendingCount == 1 && model.status.previewPorts == [3000] }
        #expect(model.status.kind == .pending)
        await service.send(
            .statusEvent(
                name: "question-resolved",
                data: .object([
                    "rpcId": .string("q"), "sessionId": .string("s"), "outcome": .string("answered"),
                ])))
        try await wait { model.status.kind == .goal }
        await service.send(.statusEvent(name: "stats", data: .object(["goal": .null])))
        try await wait { model.status.kind == .preview }
        await service.send(.statusEvent(name: "ready", data: .object(["capabilities": .object([:])])))
        try await wait { model.status.previewPorts.isEmpty }
        #expect(model.status.kind == nil)
        await model.stop()
    }
    @Test func productionComposerPlacesOneStatusAndCollapsesGoal() {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let model = ConversationModel(
            hostID: "host", sessionID: "s", service: StatusStreamService(),
            box: TranscriptSnapshotBox(keys: InMemorySecureStore(), directory: directory))
        let page = ConversationPage(model: model)
        let copy = ConversationCopy(locale: Locale(identifier: "zh-Hans"))
        var state = ConversationStatusState()
        state.apply(
            projections: .object([
                "goal": .object(["objective": .string("Fix"), "phase": .string("active")]),
                "todos": .array([
                    .object(["content": .string("Read"), "status": .string("completed")]),
                    .object(["content": .string("Test"), "status": .string("in_progress")]),
                ]),
            ]))
        model.testStatus = state
        var surface = page.composerSurface(copy: copy, expanded: true)
        #expect(surface.placement == "composer")
        #expect(surface.kind == "goal")
        #expect(surface.expanded)
        #expect(surface.showsPlan)
        #expect(surface.material == "grouped")
        #expect(surface.decisionVisible == false)
        state.question(QuestionRequestEvent(rpcId: "q"))
        model.testStatus = state
        surface = page.composerSurface(copy: copy, expanded: true)
        #expect(surface.kind == "pending")
        #expect(surface.expanded == false)
        #expect(surface.showsPlan == false)
        #expect(surface.decisionVisible)
        state.connection = .reconnecting
        model.testStatus = state
        surface = page.composerSurface(copy: copy, expanded: true)
        #expect(surface.kind == "disconnected")
        #expect(surface.expanded == false)
    }

    @Test func statusCopyHasBothLocales() {
        let en = ConversationCopy(locale: Locale(identifier: "en"))
        let zh = ConversationCopy(locale: Locale(identifier: "zh-Hans"))
        #expect(en.text(.statusGoal) == "Goal")
        #expect(zh.text(.statusGoal) == "目标")
        #expect(en.format(.statusPlan, 1, 3) == "Plan 1/3")
        #expect(zh.format(.statusPending, 2) == "2 项待处理")
    }

    private func wait(_ predicate: @MainActor () -> Bool) async throws {
        for _ in 0..<100 {
            if predicate() { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(predicate())
    }
}

private actor StatusStreamService: ConversationServing {
    private var continuation: AsyncStream<ConversationSignal>.Continuation?
    private(set) var reads = 0

    func history(sessionID: String, beforeSeq: Int?) async throws -> HistoryResponse {
        _ = (sessionID, beforeSeq)
        return HistoryResponse(messages: [], maxSeq: 0, goal: SessionGoal(objective: "Fix", phase: .active))
    }

    func open(sessionID: String, afterSeq: Int) async throws -> AsyncStream<ConversationSignal> {
        let (stream, continuation) = AsyncStream.makeStream(of: ConversationSignal.self)
        self.continuation = continuation
        return stream
    }

    func requests(sessionID: String) async throws -> RequestsSnapshotResponse? {
        reads += 1
        return RequestsSnapshotResponse(questions: [PendingQuestion(rpcId: "q", status: .pending)])
    }

    func detections() async throws -> PreviewDetectionsResponse? {
        reads += 1
        return PreviewDetectionsResponse(detections: [PreviewDetection(port: 3000, sessionId: "s")])
    }

    func send(_ signal: ConversationSignal) {
        let encoded: ConversationSignal
        if case .statusEvent(let name, let data, let raw) = signal, raw.isEmpty,
            let bytes = try? JSONEncoder().encode(data)
        {
            encoded = .statusEvent(name: name, data: data, raw: bytes)
        } else {
            encoded = signal
        }
        continuation?.yield(encoded)
    }
    func stop() async { continuation?.finish() }
}
