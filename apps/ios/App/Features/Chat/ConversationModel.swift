import DLCore
import DLModels
import DLNet
import DLSecurity
import Foundation
import Observation
import UIKit

enum ConversationServiceError: Error, Equatable {
    case missingHost
    case offline
    case unauthorized
    case certificate
    case failed
}

enum ConversationSignal: Equatable, Sendable {
    case frame(StreamFrame)
    case stats(HistoryStats)
    case statusEvent(name: String, data: JSONValue, raw: Data = Data())
    case connection(ConversationConnection)
    case resync
    case failed
}

protocol ConversationServing: Sendable {
    func history(sessionID: String, beforeSeq: Int?) async throws -> HistoryResponse
    func open(sessionID: String, afterSeq: Int) async throws -> AsyncStream<ConversationSignal>
    func requests(sessionID: String) async throws -> RequestsSnapshotResponse?
    func detections() async throws -> PreviewDetectionsResponse?
    func commit(_ seq: Int) async
    func resume(after seq: Int) async
    func setPhase(_ phase: AppPhase) async
    func stop() async
    func sendPrompt(sessionID: String, text: String, images: [PromptImage]) async throws
    func submitApproval(sessionID: String, approvalID: String, outcome: String) async throws
    func submitQuestion(sessionID: String, rpcID: String, answer: QuestionAnswerBody) async throws
    func models(sessionID: String) async throws -> SessionModelsResponse
    func selectModel(sessionID: String, provider: String, model: String, effort: String?) async throws
    func setPermission(sessionID: String, preset: String) async throws
    func renameSession(sessionID: String, title: String) async throws
    func forkSession(sessionID: String) async throws -> String?
    func schedules(sessionID: String, all: Bool) async throws -> [ScheduleTask]
    func editGoal(sessionID: String, refID: String, revision: Int, objective: String, rounds: Int) async throws
    func clearGoal(sessionID: String, refID: String, revision: Int) async throws
    func deleteSchedule(sessionID: String, scheduleID: String) async throws
    func previewExchange(path: String) async -> PreviewHTTPResult
}

extension ConversationServing {
    func history(sessionID: String, beforeSeq: Int? = nil) async throws -> HistoryResponse {
        _ = (sessionID, beforeSeq)
        throw ConversationServiceError.offline
    }

    func open(sessionID: String, afterSeq: Int) async throws -> AsyncStream<ConversationSignal> {
        _ = (sessionID, afterSeq)
        return AsyncStream { $0.finish() }
    }

    func requests(sessionID: String) async throws -> RequestsSnapshotResponse? { nil }
    func detections() async throws -> PreviewDetectionsResponse? { nil }
    func commit(_ seq: Int) async { _ = seq }
    func resume(after seq: Int) async { _ = seq }
    func setPhase(_ phase: AppPhase) async { _ = phase }
    func stop() async {}
    func sendPrompt(sessionID: String, text: String, images: [PromptImage]) async throws {
        _ = (sessionID, text, images)
        throw ConversationServiceError.offline
    }
    func submitApproval(sessionID: String, approvalID: String, outcome: String) async throws {
        _ = (sessionID, approvalID, outcome)
        throw ConversationServiceError.offline
    }
    func submitQuestion(sessionID: String, rpcID: String, answer: QuestionAnswerBody) async throws {
        _ = (sessionID, rpcID, answer)
        throw ConversationServiceError.offline
    }
    func models(sessionID: String) async throws -> SessionModelsResponse {
        _ = sessionID
        throw ConversationServiceError.offline
    }
    func selectModel(sessionID: String, provider: String, model: String, effort: String?) async throws {
        _ = (sessionID, provider, model, effort)
        throw ConversationServiceError.offline
    }
    func setPermission(sessionID: String, preset: String) async throws {
        _ = (sessionID, preset)
        throw ConversationServiceError.offline
    }
    func renameSession(sessionID: String, title: String) async throws {
        _ = (sessionID, title)
        throw ConversationServiceError.offline
    }
    func forkSession(sessionID: String) async throws -> String? {
        _ = sessionID
        throw ConversationServiceError.offline
    }
    func schedules(sessionID: String, all: Bool) async throws -> [ScheduleTask] {
        _ = (sessionID, all)
        return []
    }
    func editGoal(sessionID: String, refID: String, revision: Int, objective: String, rounds: Int) async throws {
        _ = (sessionID, refID, revision, objective, rounds)
        throw ConversationServiceError.offline
    }
    func clearGoal(sessionID: String, refID: String, revision: Int) async throws {
        _ = (sessionID, refID, revision)
        throw ConversationServiceError.offline
    }
    func deleteSchedule(sessionID: String, scheduleID: String) async throws {
        _ = (sessionID, scheduleID)
        throw ConversationServiceError.offline
    }
    func previewExchange(path: String) async -> PreviewHTTPResult {
        _ = path
        return PreviewHTTPResult(status: 502, body: Data())
    }
}

struct ConversationSeed: Equatable, Sendable {
    var title: String = ""
    var workspace: String = ""
    var running = false
    var step: Int?
    var added: Int?
    var deleted: Int?
    var stoppedReason: String?
    var awaitingInput = false
}

struct PreparedTranscript: Equatable, Sendable {
    var messages: [HistoryMessage]
    var stats: HistoryStats?
    var running: Bool
    var expanded: Set<String> = []
    var confirmingSnapshot = false
    var stoppedReason: String?
    var status: ConversationStatusState?
}

enum ChatImageState: Equatable {
    case idle
    case loading
    case blocked
    case failed
    case loaded(Data)
}

@MainActor @Observable
final class ConversationModel {
    let hostID: String
    let sessionID: String
    let autostart: Bool
    /// Snapshot rows never offer a submit control. Live approval submission is I4.3c.
    var approvalsSubmittable: Bool { false }

    private let service: any ConversationServing
    private let box: TranscriptSnapshotBox
    private let seed: ConversationSeed
    private let imageSession: URLSession
    private var buffer = FrameBuffer()
    private var seenAssistant: [String: String] = [:]
    private var streamTask: Task<Void, Never>?
    private var statusRefreshTask: Task<Void, Never>?
    private var previewRefreshTask: Task<Void, Never>?
    private var previewDetectionAvailable = false
    private var requestsAvailable = false
    private var persistTask: Task<Void, Never>?
    private var started = false
    private var loadedHistory = false
    private var draining = false

    /// Read-only SSE status. Sending remains an independent HostClient HTTP operation in I4.3c.
    private(set) var status = ConversationStatusState()
    var testStatus: ConversationStatusState {
        get { status }
        set { status = newValue }
    }
    func testSetHasOlder(_ value: Bool, beforeSeq: Int?) {
        hasOlder = value
        olderBeforeSeq = beforeSeq
    }
    var testOlderBeforeSeq: Int? { olderBeforeSeq }
    func seedForTest(messages: [HistoryMessage], hasOlder: Bool, beforeSeq: Int?) {
        self.messages = messages
        self.hasOlder = hasOlder
        olderBeforeSeq = beforeSeq
    }
    private(set) var title: String
    private(set) var workspacePath: String
    private(set) var messages: [HistoryMessage] = []
    private(set) var rows: [TranscriptRow] = []
    private(set) var running: Bool
    private(set) var stoppedReason: String?
    private(set) var confirmingSnapshot = false
    private(set) var loadFailed = false
    private(set) var hasOlder = false
    private(set) var loadingOlder = false
    private(set) var olderFailed = false
    private var olderBeforeSeq: Int?
    private(set) var expanded: Set<String> = []
    private(set) var stats: HistoryStats?
    private(set) var maxSeq = 0
    var images: [String: ChatImageState] = [:]
    var suggestedDraft = ""
    var regenerateText = ""
    var copiedText = ""
    var changesRequest: Int?
    var diffRequested = false

    init(
        hostID: String,
        sessionID: String,
        seed: ConversationSeed = ConversationSeed(),
        service: any ConversationServing,
        box: TranscriptSnapshotBox,
        prepared: PreparedTranscript? = nil,
        autostart: Bool = true,
        imageSession: URLSession? = nil
    ) {
        self.hostID = hostID
        self.sessionID = sessionID
        self.seed = seed
        self.service = service
        self.box = box
        self.title = seed.title
        self.workspacePath = seed.workspace
        self.running = seed.running
        self.stoppedReason = seed.stoppedReason
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 12
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        self.imageSession = imageSession ?? URLSession(configuration: configuration)
        if let prepared {
            self.autostart = false
            messages = prepared.messages
            stats = prepared.stats
            running = prepared.running
            expanded = prepared.expanded
            confirmingSnapshot = prepared.confirmingSnapshot
            stoppedReason = prepared.stoppedReason ?? seed.stoppedReason
            status = prepared.status ?? ConversationStatusState()
            rebuild(fade: false)
        } else {
            self.autostart = autostart
            status.awaitingHostInput = seed.awaitingInput
        }
    }

    var workspaceName: String {
        inboxWorkspaceName(workspacePath) ?? ""
    }

    var stepNumber: Int? {
        messages.compactMap(\.step).max() ?? seed.step
    }

    var phase: ConversationPhase {
        if running { return .running(step: stepNumber) }
        switch stoppedReason {
        case "error", "failed": return .failed
        case "stopped", "aborted", "interrupted", "timeout", "maxTokens": return .stopped
        case "awaitingApproval", "awaitingInput": return .awaiting
        default:
            if confirmingSnapshot, messages.contains(where: isUnconfirmedApproval) { return .awaiting }
            return .completed
        }
    }

    var added: Int? { latestChanges?.added ?? seed.added }
    var deleted: Int? { latestChanges?.deleted ?? seed.deleted }

    func start() async {
        guard !started else { return }
        started = true
        status.connection = .connecting
        showSnapshotIfPresent()
        do {
            try await reloadAndOpen()
        } catch {
            loadFailed = true
            status.connection = .failed
        }
    }

    func drainFrame() {
        guard !draining else { return }
        draining = true
        defer { draining = false }
        let frames = buffer.flush()
        guard !frames.isEmpty else { return }
        messages = reduceTranscript(messages, frames: frames)
        running = reduceRunning(running, frames: frames)
        if let seq = frames.map(\.seq).max(), seq > maxSeq { maxSeq = seq }
        confirmingSnapshot = false
        rebuild(fade: true)
        if frames.contains(where: isDurableFrame) { schedulePersist() }
        let seq = maxSeq
        Task { await self.service.commit(seq) }
    }

    func setPhase(_ phase: AppPhase) async {
        await service.setPhase(phase)
    }

    func stop() async {
        statusRefreshTask?.cancel()
        previewRefreshTask?.cancel()
        persistTask?.cancel()
        persistNow()
        streamTask?.cancel()
        streamTask = nil
        await service.stop()
    }

    func toggleProcess(_ id: String) {
        if expanded.contains(id) {
            expanded.remove(id)
        } else {
            expanded.insert(id)
        }
        rebuild(fade: false)
    }

    func copyAssistant(_ text: String) {
        copiedText = text
        UIPasteboard.general.string = text
    }

    func regenerate(_ text: String) {
        regenerateText = text
    }

    func suggest(_ text: String) {
        suggestedDraft = text
    }

    func viewChanges(seq: Int?) {
        changesRequest = seq
    }

    func noteDiff() {
        diffRequested = true
    }

    func loadImage(_ raw: String) async {
        switch imageLoadDecision(raw) {
        case .blocked:
            images[raw] = .blocked
        case .allowed(let url):
            if case .loaded = images[raw] { return }
            if images[raw] == .loading { return }
            images[raw] = .loading
            do {
                var request = URLRequest(url: url)
                request.timeoutInterval = 12
                let (data, response) = try await imageSession.data(for: request)
                guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                    http.url?.scheme?.lowercased() == "https", UIImage(data: data) != nil
                else {
                    images[raw] = .failed
                    return
                }
                images[raw] = .loaded(data)
            } catch {
                images[raw] = .failed
            }
        }
    }

    private func showSnapshotIfPresent() {
        guard let data = try? box.read(hostID: hostID, sessionID: sessionID),
            let record = try? TranscriptSnapshotCoding.decode(data)
        else { return }
        messages = record.messages
        stats = record.stats
        maxSeq = record.maxSeq
        if !record.title.isEmpty { title = record.title }
        if !record.workspace.isEmpty { workspacePath = record.workspace }
        running = record.running
        confirmingSnapshot = true
        loadFailed = false
        rebuild(fade: false)
    }

    private func replace(_ history: HistoryResponse) {
        buffer.discard()
        messages = history.messages ?? []
        if let next = history.stats { stats = next }
        if let seq = history.maxSeq { maxSeq = seq }
        status.replace(history: history)
        if !loadedHistory, seed.awaitingInput { status.awaitingHostInput = true }
        loadedHistory = true
        stoppedReason = history.stoppedReason ?? seed.stoppedReason
        confirmingSnapshot = false
        loadFailed = false
        hasOlder = history.hasMore == true
        olderBeforeSeq = history.nextBeforeSeq
        olderFailed = false
        if stoppedReason != nil {
            running = false
        } else {
            running = seed.running || messages.contains { $0.running == true }
        }
        rebuild(fade: false)
        persistNow()
    }

    private func prepend(_ page: HistoryResponse) {
        messages = prependHistoryPage(page.messages ?? [], before: messages)
        hasOlder = page.hasMore == true
        olderBeforeSeq = page.nextBeforeSeq
        olderFailed = false
        rebuild(fade: false)
    }

    private func ingest(_ signal: ConversationSignal) async {
        switch signal {
        case .frame(let frame):
            status.absorb(frame)
            buffer.append([frame])
            if frame.type == "tool/result" { refreshPreviews(debounce: true) }
        case .stats(let next):
            stats = next
            rebuild(fade: false)
        case .connection(let connection):
            status.connection = connection
            if connection == .connected { loadFailed = false }
        case .statusEvent(let name, let data, let raw):
            absorbStatusEvent(name, data: data, raw: raw)
        case .resync:
            status.connection = .reconnecting
            buffer.discard()
            if let history = try? await service.history(sessionID: sessionID, beforeSeq: nil) {
                replace(history)
                await service.resume(after: maxSeq)
            } else {
                status.connection = .failed
            }
        case .failed:
            status.connection = .failed
            loadFailed = true
        }
    }

    private func absorbStatusEvent(_ name: String, data: JSONValue, raw: Data) {
        let decoder = JSONDecoder().preservingRawJSON(raw)
        switch name {
        case "ready":
            guard let ready = try? decoder.decode(StreamReadyEvent.self, from: raw) else { return }
            requestsAvailable = ready.capabilities?.requests?.snapshot == true
            previewDetectionAvailable = ready.capabilities?.preview?.detect == 1
            previewRefreshTask?.cancel()
            if !previewDetectionAvailable {
                status.apply(detections: PreviewDetectionsResponse(detections: []), sessionID: sessionID)
            }
            statusRefreshTask?.cancel()
            if requestsAvailable {
                statusRefreshTask = Task { [weak self] in
                    guard let self else { return }
                    if let snapshot = try? await service.requests(sessionID: sessionID), !Task.isCancelled {
                        status.merge(requests: snapshot)
                    }
                }
            }
            refreshPreviews(debounce: false)
        case "stats":
            status.apply(projections: data)
        case "question":
            if let event = try? decoder.decode(QuestionRequestEvent.self, from: raw),
                event.sessionId == nil || event.sessionId == sessionID
            {
                status.question(event)
            }
        case "question-resolved":
            if let event = try? decoder.decode(QuestionResolvedEvent.self, from: raw),
                event.sessionId == nil || event.sessionId == sessionID
            {
                status.resolveQuestion(event)
            }
        default: break
        }
    }

    private func refreshPreviews(debounce: Bool) {
        guard previewDetectionAvailable else { return }
        previewRefreshTask?.cancel()
        previewRefreshTask = Task { [weak self] in
            guard let self else { return }
            if debounce { try? await Task.sleep(for: .milliseconds(500)) }
            guard !Task.isCancelled else { return }
            if let detections = try? await service.detections(), !Task.isCancelled {
                status.apply(detections: detections, sessionID: sessionID)
            }
        }
    }

    func serviceSend(_ text: String, images: [PromptImage] = []) async throws {
        try await service.sendPrompt(sessionID: sessionID, text: text, images: images)
    }

    func serviceApproval(id: String, outcome: String) async throws {
        try await service.submitApproval(sessionID: sessionID, approvalID: id, outcome: outcome)
    }

    func serviceQuestion(rpcID: String, answer: QuestionAnswerBody) async throws {
        try await service.submitQuestion(sessionID: sessionID, rpcID: rpcID, answer: answer)
        status.requests.update(requestId: rpcID, status: .resolved, outcome: "answered")
    }

    /// 向上翻一页。失败只标记，不替换当前尾页。
    func loadOlder() async {
        guard hasOlder, !loadingOlder, let before = olderBeforeSeq, before > 0 else { return }
        loadingOlder = true
        olderFailed = false
        defer { loadingOlder = false }
        do {
            let page = try await service.history(sessionID: sessionID, beforeSeq: before)
            prepend(page)
        } catch {
            olderFailed = true
        }
    }

    /// 断线状态槽的重试：重新拉尾页并恢复流，不碰凭据。
    func retryConnection() async {
        status.connection = .connecting
        loadFailed = false
        do {
            try await reloadAndOpen()
        } catch {
            loadFailed = true
            status.connection = .failed
        }
    }

    private func reloadAndOpen() async throws {
        let history = try await service.history(sessionID: sessionID, beforeSeq: nil)
        replace(history)
        streamTask?.cancel()
        await service.stop()
        let stream = try await service.open(sessionID: sessionID, afterSeq: maxSeq)
        streamTask = Task { [weak self] in
            guard let self else { return }
            for await signal in stream {
                if Task.isCancelled { break }
                await self.ingest(signal)
            }
        }
        status.connection = .connected
    }

    func serviceModels() async -> [ModelRow] {
        guard let response = try? await service.models(sessionID: sessionID) else { return [] }
        return modelRows(response)
    }

    func serviceSelectModel(provider: String, model: String, effort: String?) async {
        try? await service.selectModel(sessionID: sessionID, provider: provider, model: model, effort: effort)
    }

    func servicePermission(_ preset: String) async {
        try? await service.setPermission(sessionID: sessionID, preset: preset)
    }

    func serviceRename(_ title: String) async throws {
        try await service.renameSession(sessionID: sessionID, title: title)
        self.title = title
    }

    func serviceFork() async throws -> String? {
        try await service.forkSession(sessionID: sessionID)
    }

    func serviceSchedules(all: Bool) async -> [ScheduleTask] {
        (try? await service.schedules(sessionID: sessionID, all: all)) ?? []
    }

    func serviceEditGoal(objective: String, rounds: Int) async {
        guard let id = status.goal?.ref?.id, let revision = status.goal?.ref?.revision else { return }
        do {
            try await service.editGoal(
                sessionID: sessionID, refID: id, revision: revision, objective: objective, rounds: rounds)
            status.goal?.objective = objective
            status.goal?.maxGoalRounds = rounds
        } catch {}
    }

    func serviceClearGoal() async {
        guard let id = status.goal?.ref?.id, let revision = status.goal?.ref?.revision else { return }
        do {
            try await service.clearGoal(sessionID: sessionID, refID: id, revision: revision)
            status.goal = nil
        } catch {}
    }

    func previewForwarder() -> @Sendable (String) async -> PreviewHTTPResult {
        let service = service
        return { path in
            await service.previewExchange(path: path)
        }
    }

    func serviceDeleteSchedule(_ id: String) async {
        try? await service.deleteSchedule(sessionID: sessionID, scheduleID: id)
    }

    private func rebuild(fade: Bool) {
        let projected = projectTranscript(
            messages: messages, running: running, stats: stats, expandedProcessIDs: expanded,
            confirmingSnapshot: confirmingSnapshot)
        if fade {
            rows = markFreshAssistants(projected, seen: &seenAssistant)
        } else {
            rows = projected
            rememberAssistants(projected, seen: &seenAssistant)
        }
    }

    private func schedulePersist() {
        persistTask?.cancel()
        persistTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled else { return }
            self?.persistNow()
        }
    }

    private func persistNow() {
        let record = TranscriptSnapshotRecord(
            messages: messages, stats: stats, maxSeq: maxSeq, title: title, workspace: workspacePath, running: running,
            step: stepNumber)
        guard let data = try? TranscriptSnapshotCoding.encode(record) else { return }
        try? box.write(hostID: hostID, sessionID: sessionID, plaintext: data)
    }

    private var latestChanges: ChangesSummary? {
        messages.last { $0.role == "workspace_changes" }?.changes
    }
}
