import Foundation

/// 解析后的单条 SSE 事件（I3.5）。
///
/// `id` 是当前 lastEventID：本帧有 `id:` 行时取该值，没有时保留上一次的值
/// （SSE 规范：lastEventId 只在派发时更新）；`event` 为 nil 表示帧里没有 `event:` 行
/// （规范默认类型是 `message`，本项目的流总是显式命名事件，上层按需兜底）。
public struct SSEEvent: Equatable, Sendable {
    public var id: String?
    public var event: String?
    public var data: String
    /// 帧里 `retry:` 的毫秒数（非法值忽略；插件当前不发该字段，保留以便对齐规范）。
    public var retry: Int?

    public init(id: String? = nil, event: String? = nil, data: String = "", retry: Int? = nil) {
        self.id = id
        self.event = event
        self.data = data
        self.retry = retry
    }
}

/// 纯 SSE 行解析器（I3.5）：把行流解析成 `SSEEvent`，与传输、重连逻辑完全分开，方便测试。
///
/// 遵守 SSE 规范（WHATWG「Server-sent events」解析步骤）：
/// - 空行分帧并派发；
/// - 以 `:` 开头的行是注释/保活，忽略；
/// - `data` 多行以 `\n` 拼接；
/// - `id` 为空串时清除 lastEventID，否则在派发时更新；
/// - data 缓冲为空的帧不派发（规范：清空 data 与 event 缓冲后返回）。
/// 行尾 `\r`（CRLF 流）在进解析前剥掉。
public struct SSELineParser: Sendable {
    private var dataLines: [String] = []
    private var eventName: String?
    private var frameID: String?
    private var lastEventID: String?
    private var retryMillis: Int?

    public init() {}

    /// 派发过的最后一个事件 id（诊断用）。
    public var lastID: String? { lastEventID }

    /// 喂一行；凑齐一帧（遇到空行且有 data）时返回解析出的事件，否则返回 nil。
    public mutating func feed(_ line: String) -> SSEEvent? {
        let trimmed = line.hasSuffix("\r") ? String(line.dropLast()) : line
        if trimmed.isEmpty {
            return dispatch()
        }
        // 注释 / 保活行。
        guard !trimmed.hasPrefix(":") else { return nil }
        let (field, value) = Self.splitField(trimmed)
        switch field {
        case "data":
            dataLines.append(value)
        case "event":
            eventName = value
        case "id":
            frameID = value
        case "retry":
            retryMillis = Int(value)
        default:
            break  // 未知字段按规范忽略
        }
        return nil
    }

    private mutating func dispatch() -> SSEEvent? {
        guard !dataLines.isEmpty else {
            // 规范：data 缓冲为空 → 清空 data 与 event 缓冲后返回，不派发。
            dataLines = []
            eventName = nil
            return nil
        }
        if let frameID {
            lastEventID = frameID.isEmpty ? nil : frameID
        }
        let event = SSEEvent(
            id: lastEventID,
            event: eventName,
            data: dataLines.joined(separator: "\n"),
            retry: retryMillis
        )
        dataLines = []
        eventName = nil
        frameID = nil
        retryMillis = nil
        return event
    }

    /// 第一个 `:` 前是字段名，其后是值（至多剥一个前导空格）。
    private static func splitField(_ line: String) -> (field: String, value: String) {
        guard let colon = line.firstIndex(of: ":") else {
            return (line, "")
        }
        var value = line[line.index(after: colon)...]
        if value.hasPrefix(" ") {
            value.removeFirst()
        }
        return (String(line[..<colon]), String(value))
    }
}
