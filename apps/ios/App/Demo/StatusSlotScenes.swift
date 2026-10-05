import DLCore
import DLModels
import DLSecurity
import Foundation

enum StatusSlotScene: String, CaseIterable {
    case collapsed
    case expanded
    case disconnected
    case pending
    case preview
    case connecting
    case failed
    case planOnly
    case noPlan
    case completed
    case empty

    var snapshotScene: String {
        "\(self == .disconnected || self == .connecting || self == .failed ? "4_8" : "4_5")_status_\(rawValue)"
    }
}

/// Offline fixture content only. UI copy continues to use the String Catalog.
enum StatusSlotScenes {
    @MainActor static func model(_ scene: StatusSlotScene, locale: Locale) -> ConversationModel {
        let copy = ConversationCopy(locale: locale)
        let goal = SessionGoal(
            objective: copy.text(.statusDemoObjective),
            phase: scene == .completed ? .complete : .active, maxGoalRounds: 8, roundsStarted: 3)
        let plan = [
            TodoItem(content: copy.text(.statusDemoFind), status: "completed"),
            TodoItem(content: copy.text(.statusDemoBroadcast), status: "completed"),
            TodoItem(content: copy.text(.statusDemoExpiry), status: "in_progress"),
            TodoItem(content: copy.text(.statusDemoVerify), status: "pending"),
        ]
        let visiblePlan = scene == .completed ? plan.map { TodoItem(content: $0.content, status: "completed") } : plan
        var status = ConversationStatusState()
        if ![.preview, .empty, .planOnly].contains(scene) { status.goal = goal }
        if ![.preview, .empty, .noPlan].contains(scene),
            let bytes = try? JSONEncoder().encode(visiblePlan),
            let todos = try? JSONDecoder().decode(JSONValue.self, from: bytes)
        {
            status.apply(projections: .object(["todos": todos]))
        }
        switch scene {
        case .disconnected: status.connection = .reconnecting
        case .connecting: status.connection = .connecting
        case .failed: status.connection = .failed
        case .pending: status.question(QuestionRequestEvent(rpcId: "demo-question"))
        case .preview:
            status.apply(
                detections: PreviewDetectionsResponse(detections: [PreviewDetection(port: 3000, sessionId: "demo")]),
                sessionID: "demo")
        default: break
        }
        return ConversationModel(
            hostID: "demo-status", sessionID: "demo",
            seed: ConversationSeed(title: copy.text(.statusDemoTitle), workspace: "/work/deeplinks"),
            service: StatusSlotDemoService(),
            box: TranscriptSnapshotBox(
                keys: InMemorySecureStore(), directory: FileManager.default.temporaryDirectory),
            prepared: PreparedTranscript(
                messages: [
                    HistoryMessage(
                        id: "user", role: "user", kind: .user,
                        text: copy.text(.statusDemoUser)),
                    HistoryMessage(
                        id: "assistant", role: "assistant", kind: .role("assistant"),
                        text: copy.text(.statusDemoAssistant)),
                ], running: true, status: status), autostart: false)
    }
}

private struct StatusSlotDemoService: ConversationServing {}
