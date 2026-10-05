import DLModels
import Foundation

public enum ConversationConnection: Equatable, Sendable {
    case connected
    case connecting
    case reconnecting
    case failed
}

public enum ConversationStatusKind: Equatable, Sendable {
    case disconnected
    case pending
    case goal
    case preview
}

public enum ConversationPlanKind: Equatable, Sendable {
    case pending
    case active
    case completed
}

/// Same aliases as Android PlanChecklist; unknown states remain unfinished.
public func conversationPlanKind(_ status: String?) -> ConversationPlanKind {
    switch status {
    case "done", "completed", "complete": .completed
    case "in_progress", "inprogress", "running", "active", "progress": .active
    default: .pending
    }
}

/// Read-only projection for 4.5 / 4.8. SSE availability never controls HTTP sending.
public struct ConversationStatusState: Equatable, Sendable {
    public var connection: ConversationConnection = .connected
    public var goal: SessionGoal?
    public private(set) var plan: [TodoItem] = []
    public private(set) var previewPorts: [Int] = []
    public private(set) var requests = RequestStateReducer()
    public var awaitingHostInput = false
    private var maxSeq = 0

    public init() {}

    public var pendingCount: Int {
        requests.messages.filter { $0.requestStatus == .pending }.count
    }

    public var kind: ConversationStatusKind? {
        if connection != .connected { return .disconnected }
        if pendingCount > 0 || awaitingHostInput { return .pending }
        if goal != nil || !plan.isEmpty { return .goal }
        if !previewPorts.isEmpty { return .preview }
        return nil
    }

    public var completedCount: Int { plan.filter { conversationPlanKind($0.status) == .completed }.count }
    public var progress: Double? {
        plan.isEmpty ? nil : Double(completedCount) / Double(plan.count)
    }

    public mutating func replace(history: HistoryResponse) {
        maxSeq = history.maxSeq ?? 0
        goal = Self.visibleGoal(history.goal)
        plan =
            Self.todos(history.stats?.todos)
            ?? history.messages?.last(where: { $0.todos != nil })?.todos ?? []
        plan = Self.visiblePlan(plan)
        awaitingHostInput = history.stoppedReason == "awaitingApproval" || history.stoppedReason == "awaitingInput"
        requests.absorb(
            (history.messages ?? []).filter { $0.role == "approval" }.compactMap { message in
                guard let id = message.approvalId, !id.isEmpty else { return nil }
                return RequestMessage(
                    id: message.id ?? id, role: "approval", approvalId: id,
                    requestStatus: message.requestStatus ?? approvalUiStatus(outcome: message.outcome),
                    outcome: message.outcome)
            })
    }

    /// stats is the complete DSH projection map. Missing keys preserve; explicit null clears.
    public mutating func apply(projections: JSONValue) {
        guard let values = projections.objectValue else { return }
        if let value = values["goal"] {
            if value == .null {
                goal = nil
            } else if let decoded: SessionGoal = Self.decode(value) {
                goal = Self.visibleGoal(decoded)
            }
        }
        if let value = values["todos"], let todos = Self.todos(value) {
            plan = Self.visiblePlan(todos)
        }
    }

    public mutating func merge(requests snapshot: RequestsSnapshotResponse) {
        requests.merge(snapshot: snapshot)
    }

    public mutating func apply(detections: PreviewDetectionsResponse, sessionID: String) {
        previewPorts = Array(
            Set(
                (detections.detections ?? []).compactMap { item in
                    guard item.sessionId == sessionID, let port = item.port, (1...65535).contains(port) else {
                        return nil
                    }
                    return port
                })
        ).sorted()
    }

    public mutating func absorb(_ frame: StreamFrame) {
        guard frame.seq > maxSeq else { return }
        maxSeq = frame.seq
        let data = frame.data.objectValue ?? [:]
        switch frame.type {
        case "todo/write":
            if let value = data["todos"], let todos = Self.todos(value) { plan = Self.visiblePlan(todos) }
        case "approval/asked":
            guard let id = data["id"]?.stringValue, !id.isEmpty else { return }
            requests.absorb([RequestMessage(id: id, role: "approval", approvalId: id, requestStatus: .pending)])
        case "approval/decided":
            guard let id = data["id"]?.stringValue, !id.isEmpty else { return }
            // Preserve a terminal even when the asked event was outside the history tail.
            requests.absorb([
                RequestMessage(
                    id: id, role: "approval", approvalId: id,
                    requestStatus: approvalUiStatus(outcome: data["outcome"]?.stringValue),
                    outcome: data["outcome"]?.stringValue)
            ])
            awaitingHostInput = false
        case "turn/start":
            awaitingHostInput = false
        case "turn/end":
            let reason = data["reason"]?.objectValue?["kind"]?.stringValue
            awaitingHostInput = reason == "awaitingApproval" || reason == "awaitingInput"
        default: break
        }
    }

    public mutating func question(_ event: QuestionRequestEvent) {
        guard let id = event.rpcId, !id.isEmpty else { return }
        requests.absorb([RequestMessage(id: id, role: "question", questionRpcId: id, requestStatus: .pending)])
    }

    public mutating func resolveQuestion(_ event: QuestionResolvedEvent) {
        guard let id = event.rpcId, !id.isEmpty else { return }
        requests.absorb([
            RequestMessage(
                id: id, role: "question", questionRpcId: id,
                requestStatus: event.outcome == "answered" ? .resolved : .cancelled, outcome: event.outcome)
        ])
        awaitingHostInput = false
    }

    private static func visibleGoal(_ goal: SessionGoal?) -> SessionGoal? {
        guard let goal, !(goal.objective ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        return goal
    }

    private static func visiblePlan(_ items: [TodoItem]) -> [TodoItem] {
        items.filter { !($0.content ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }

    private static func todos(_ value: JSONValue?) -> [TodoItem]? {
        guard let value else { return nil }
        if value == .null { return [] }
        // DSH versions may wrap the list in the todos projection.
        if let nested = value.objectValue?["todos"] { return decode(nested) }
        return decode(value)
    }

    private static func decode<T: Decodable>(_ value: JSONValue) -> T? {
        guard let data = try? JSONEncoder().encode(value) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }
}
