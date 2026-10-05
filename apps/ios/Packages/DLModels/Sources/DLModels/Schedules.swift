import Foundation

/// `GET /dsh-link/mobile/schedules` 与 `GET /sessions/:id/schedules`。
public struct ScheduleListResponse: Codable, Equatable, Sendable {
    public var ok: Bool?
    public var sessionId: String?
    public var items: [ScheduleTask]?

    public init(ok: Bool? = nil, sessionId: String? = nil, items: [ScheduleTask]? = nil) {
        self.ok = ok
        self.sessionId = sessionId
        self.items = items
    }
}

public struct ScheduleTask: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var sessionId: String?
    public var title: String?
    public var prompt: String?
    public var kind: String?
    public var status: String?
    public var time: String?
    public var timeZone: String?
    public var everySeconds: Int?
    public var expression: String?
    public var weekdays: [Int]?
    public var scheduledAt: String?

    public init(
        id: String,
        sessionId: String? = nil,
        title: String? = nil,
        prompt: String? = nil,
        kind: String? = nil,
        status: String? = nil,
        time: String? = nil,
        timeZone: String? = nil,
        everySeconds: Int? = nil,
        expression: String? = nil,
        weekdays: [Int]? = nil,
        scheduledAt: String? = nil
    ) {
        self.id = id
        self.sessionId = sessionId
        self.title = title
        self.prompt = prompt
        self.kind = kind
        self.status = status
        self.time = time
        self.timeZone = timeZone
        self.everySeconds = everySeconds
        self.expression = expression
        self.weekdays = weekdays
        self.scheduledAt = scheduledAt
    }
}
