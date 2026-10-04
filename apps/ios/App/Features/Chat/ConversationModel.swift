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
    case resync
    case failed
}

protocol ConversationServing: Sendable {
    func history(sessionID: String) async throws -> HistoryResponse
    func open(sessionID: String, afterSeq: Int) async throws -> AsyncStream<ConversationSignal>
    func commit(_ seq: Int) async
    func resume(after seq: Int) async
    func setPhase(_ phase: AppPhase) async
    func stop() async
}

extension ConversationServing {
    func history(sessionID: String) async throws -> HistoryResponse {
        _ = sessionID
        throw ConversationServiceError.offline
    }

    func open(sessionID: String, afterSeq: Int) async throws -> AsyncStream<ConversationSignal> {
        _ = (sessionID, afterSeq)
        return AsyncStream { $0.finish() }
    }

    func commit(_ seq: Int) async { _ = seq }
    func resume(after seq: Int) async { _ = seq }
    func setPhase(_ phase: AppPhase) async { _ = phase }
    func stop() async {}
}

struct ConversationSeed: Equatable, Sendable {
    var title: String = ""
    var workspace: String = ""
    var running = false
    var step: Int?
    var added: Int?
    var deleted: Int?
    var stoppedReason: String?
}

struct PreparedTranscript: Equatable, Sendable {
    var messages: [HistoryMessage]
    var stats: HistoryStats?
    var running: Bool
    var expanded: Set<String> = []
    var confirmingSnapshot = false
    var stoppedReason: String?
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
    private var persistTask: Task<Void, Never>?
    private var started = false
    private var draining = false

    private(set) var title: String
    private(set) var workspacePath: String
    private(set) var messages: [HistoryMessage] = []
    private(set) var rows: [TranscriptRow] = []
    private(set) var running: Bool
    private(set) var stoppedReason: String?
    private(set) var confirmingSnapshot = false
    private(set) var loadFailed = false
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
            rebuild(fade: false)
        } else {
            self.autostart = autostart
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
        showSnapshotIfPresent()
        do {
            let history = try await service.history(sessionID: sessionID)
            replace(history)
            let stream = try await service.open(sessionID: sessionID, afterSeq: maxSeq)
            streamTask = Task { [weak self] in
                guard let self else { return }
                for await signal in stream {
                    if Task.isCancelled { break }
                    await self.ingest(signal)
                }
            }
        } catch {
            loadFailed = true
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
        stoppedReason = history.stoppedReason ?? seed.stoppedReason
        confirmingSnapshot = false
        loadFailed = false
        if stoppedReason != nil {
            running = false
        } else {
            running = seed.running || messages.contains { $0.running == true }
        }
        rebuild(fade: false)
        persistNow()
    }

    private func ingest(_ signal: ConversationSignal) async {
        switch signal {
        case .frame(let frame):
            buffer.append([frame])
        case .stats(let next):
            stats = next
            rebuild(fade: false)
        case .resync:
            buffer.discard()
            if let history = try? await service.history(sessionID: sessionID) {
                replace(history)
                await service.resume(after: maxSeq)
            }
        case .failed:
            loadFailed = true
        }
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
