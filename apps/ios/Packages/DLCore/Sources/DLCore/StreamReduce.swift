import DLModels
import Foundation

/// 会话流里的一帧。`assistant/chunk` 的相邻增量按帧合并，其余帧保持原位。
public struct StreamFrame: Equatable, Sendable {
    public var seq: Int
    public var type: String
    public var time: Int
    public var data: JSONValue

    public init(seq: Int, type: String, time: Int, data: JSONValue) {
        self.seq = seq
        self.type = type
        self.time = time
        self.data = data
    }
}

public struct FrameBuffer: Equatable, Sendable {
    private var pending: [StreamFrame] = []

    public init() {}

    public var isEmpty: Bool { pending.isEmpty }

    public mutating func append(_ frames: [StreamFrame]) {
        pending.append(contentsOf: frames)
    }

    public mutating func discard() {
        pending.removeAll()
    }

    /// 取出最多 `limit` 帧并合并相邻增量。一帧时钟调用一次。
    public mutating func flush(limit: Int = 512) -> [StreamFrame] {
        guard !pending.isEmpty else { return [] }
        let count = min(max(1, limit), pending.count)
        let batch = Array(pending.prefix(count))
        pending.removeFirst(count)
        return coalesceStreamDeltas(batch)
    }
}

public func coalesceStreamDeltas(_ frames: [StreamFrame]) -> [StreamFrame] {
    guard frames.count >= 2 else { return frames }
    var output: [StreamFrame] = []
    var index = 0
    while index < frames.count {
        let key = deltaKey(frames[index])
        var end = index + 1
        if key != nil {
            while end < frames.count, deltaKey(frames[end]) == key { end += 1 }
        }
        if end - index > 1 {
            output.append(mergeDeltas(Array(frames[index..<end])))
        } else {
            output.append(frames[index])
        }
        index = end
    }
    return output
}

public func reduceTranscript(_ messages: [HistoryMessage], frames: [StreamFrame]) -> [HistoryMessage] {
    var copy = messages
    for frame in frames {
        apply(frame, to: &copy)
    }
    return copy
}

public func reduceRunning(_ running: Bool, frames: [StreamFrame]) -> Bool {
    var value = running
    for frame in frames {
        if frame.type == "turn/end" {
            value = false
        } else if isLiveActivity(frame) {
            value = true
        }
    }
    return value
}

public func isDurableFrame(_ frame: StreamFrame) -> Bool {
    if frame.type != "assistant/chunk" { return true }
    return chunk(frame)?.type == "block-end"
}

private struct ChunkFields {
    var type: String
    var turn: Int
    var index: Int
    var text: String
    var arguments: String
    var name: String
    var callID: String
    var block: [String: JSONValue]
}

private let deltaField = [
    "text-delta": "text",
    "reasoning-delta": "text",
    "tool-call-delta": "argumentsDelta",
]

private func deltaKey(_ frame: StreamFrame) -> String? {
    guard frame.type == "assistant/chunk", let chunk = chunk(frame), deltaField[chunk.type] != nil else { return nil }
    return "\(chunk.type):\(chunk.turn):\(chunk.index)"
}

private func mergeDeltas(_ frames: [StreamFrame]) -> StreamFrame {
    guard let first = frames.first, var object = first.data.objectValue, var chunkObject = object["chunk"]?.objectValue,
        let field = deltaField[chunkObject["type"]?.stringValue ?? ""]
    else { return frames[frames.count - 1] }
    var text = ""
    var name = ""
    var callID = ""
    for frame in frames {
        guard let piece = chunk(frame) else { continue }
        text += field == "argumentsDelta" ? piece.arguments : piece.text
        if !piece.name.isEmpty { name = piece.name }
        if !piece.callID.isEmpty { callID = piece.callID }
    }
    chunkObject[field] = .string(text)
    if !name.isEmpty { chunkObject["name"] = .string(name) }
    if !callID.isEmpty { chunkObject["id"] = .string(callID) }
    object["chunk"] = .object(chunkObject)
    return StreamFrame(seq: frames[frames.count - 1].seq, type: first.type, time: first.time, data: .object(object))
}

private func apply(_ frame: StreamFrame, to messages: inout [HistoryMessage]) {
    switch frame.type {
    case "assistant/chunk":
        guard let chunk = chunk(frame) else { return }
        applyChunk(chunk, time: frame.time, to: &messages)
    case "user/message":
        let id = "msg-\(frame.seq)"
        guard !messages.contains(where: { $0.seq == frame.seq || $0.id == id }) else { return }
        let text = userText(frame.data)
        messages.append(
            HistoryMessage(
                id: id, seq: frame.seq, role: "user", kind: .user, text: text, time: frame.time,
                type: "text"))
    case "tool/call":
        let id = "tool-\(frame.seq)"
        guard !messages.contains(where: { $0.seq == frame.seq || $0.id == id }) else { return }
        let data = frame.data.objectValue ?? [:]
        messages.append(
            HistoryMessage(
                id: id, seq: frame.seq, role: "tool_call", kind: .role("tool_call"), time: frame.time,
                type: "tool_call", callId: data["callId"]?.stringValue, name: data["name"]?.stringValue,
                args: argumentsText(data["arguments"]), turn: data["turn"]?.intValue, step: data["step"]?.intValue))
    case "tool/result":
        let id = "tool-res-\(frame.seq)"
        guard !messages.contains(where: { $0.seq == frame.seq || $0.id == id }) else { return }
        let data = frame.data.objectValue ?? [:]
        messages.append(
            HistoryMessage(
                id: id, seq: frame.seq, role: "tool_result", kind: .role("tool_result"),
                text: resultText(data), time: frame.time, type: "tool_result", callId: data["callId"]?.stringValue,
                turn: data["turn"]?.intValue, step: data["step"]?.intValue))
    case "approval/asked", "approval/decided":
        applyApproval(frame, to: &messages)
    case "workspace/changes":
        applyChanges(frame, to: &messages)
    default:
        break
    }
}

private func applyChunk(_ chunk: ChunkFields, time: Int, to messages: inout [HistoryMessage]) {
    switch chunk.type {
    case "reasoning-delta":
        let id = "reason-\(chunk.turn)-\(chunk.index)"
        if let index = messages.lastIndex(where: { $0.id == id }) {
            messages[index].text = (messages[index].text ?? "") + chunk.text
        } else if !chunk.text.isEmpty {
            messages.append(
                HistoryMessage(
                    id: id, role: "reasoning", kind: .role("reasoning"), text: chunk.text, time: time,
                    type: "reasoning",
                    running: true))
        }
    case "text-delta":
        let id = "msg-stream-\(chunk.turn)-\(chunk.index)"
        if let index = messages.lastIndex(where: { $0.id == id }) {
            messages[index].text = (messages[index].text ?? "") + chunk.text
            messages[index].running = true
        } else if !chunk.text.isEmpty {
            messages.append(
                HistoryMessage(
                    id: id, role: "assistant", kind: .role("assistant"), text: chunk.text, time: time, type: "text",
                    running: true))
        }
    case "tool-call-delta":
        let id = "tool-stream-\(chunk.turn)-\(chunk.index)"
        if let index = messages.lastIndex(where: { $0.id == id }) {
            messages[index].args = (messages[index].args ?? "") + chunk.arguments
            if !chunk.name.isEmpty { messages[index].name = chunk.name }
            if !chunk.callID.isEmpty { messages[index].callId = chunk.callID }
        } else {
            messages.append(
                HistoryMessage(
                    id: id, role: "tool_call", kind: .role("tool_call"), time: time, type: "tool_call",
                    callId: chunk.callID.isEmpty ? nil : chunk.callID,
                    name: chunk.name.isEmpty ? nil : chunk.name, args: chunk.arguments, running: true))
        }
    case "block-end":
        let blockType = chunk.block["type"]?.stringValue ?? ""
        switch blockType {
        case "reasoning":
            settle(
                id: "reason-\(chunk.turn)-\(chunk.index)", role: "reasoning", text: chunk.block["text"]?.stringValue,
                time: time, to: &messages)
        case "text":
            settle(
                id: "msg-stream-\(chunk.turn)-\(chunk.index)", role: "assistant",
                text: chunk.block["text"]?.stringValue, time: time, to: &messages)
        case "tool-call":
            let id = "tool-stream-\(chunk.turn)-\(chunk.index)"
            if let index = messages.lastIndex(where: { $0.id == id }) {
                if let name = nonEmpty(chunk.block["name"]?.stringValue) { messages[index].name = name }
                if let args = nonEmpty(chunk.block["arguments"]?.stringValue) { messages[index].args = args }
                if let callID = nonEmpty(chunk.block["id"]?.stringValue) { messages[index].callId = callID }
                messages[index].running = false
            }
        default:
            break
        }
    default:
        break
    }
}

private func settle(id: String, role: String, text: String?, time: Int, to messages: inout [HistoryMessage]) {
    let authoritative = text ?? ""
    if let index = messages.lastIndex(where: { $0.id == id }) {
        if !authoritative.isEmpty { messages[index].text = authoritative }
        messages[index].running = false
        return
    }
    guard !authoritative.isEmpty else { return }
    messages.append(
        HistoryMessage(
            id: id, role: role, kind: .role(role), text: authoritative, time: time,
            type: role == "assistant" ? "text" : role,
            running: false))
}

private func applyApproval(_ frame: StreamFrame, to messages: inout [HistoryMessage]) {
    let data = frame.data.objectValue ?? [:]
    let approvalID = data["id"]?.stringValue ?? ""
    let id = approvalID.isEmpty ? "approval-\(frame.seq)" : "approval-\(approvalID)"
    let pending = frame.type == "approval/asked"
    let message = HistoryMessage(
        id: id, seq: frame.seq, role: "approval", kind: .role("approval"),
        text: data["reason"]?.stringValue ?? data["toolName"]?.stringValue, time: frame.time, type: "approval",
        toolName: data["toolName"]?.stringValue, approvalId: approvalID.isEmpty ? nil : approvalID,
        callId: data["callId"]?.stringValue,
        requestStatus: pending ? .pending : .resolved, outcome: pending ? nil : data["outcome"]?.stringValue)
    if let index = messages.lastIndex(where: { $0.id == id || ($0.approvalId == approvalID && !approvalID.isEmpty) }) {
        messages[index].requestStatus = message.requestStatus
        messages[index].outcome = message.outcome
    } else {
        messages.append(message)
    }
}

private func applyChanges(_ frame: StreamFrame, to messages: inout [HistoryMessage]) {
    guard let encoded = try? JSONEncoder().encode(frame.data),
        let summary = try? JSONDecoder().decode(ChangesSummary.self, from: encoded),
        summary.files != nil || summary.total != nil
    else { return }
    let turn = summary.turn ?? frame.data.objectValue?["turn"]?.intValue
    let id = "changes-\(frame.seq)"
    if messages.contains(where: { $0.seq == frame.seq || $0.id == id }) { return }
    if let turn, let index = messages.lastIndex(where: { $0.role == "workspace_changes" && $0.turn == turn }) {
        messages.remove(at: index)
    }
    messages.append(
        HistoryMessage(
            id: id, seq: frame.seq, role: "workspace_changes", kind: .role("workspace_changes"),
            time: frame.time, type: "workspace_changes", turn: turn, changes: summary))
}

private func isLiveActivity(_ frame: StreamFrame) -> Bool {
    if frame.type == "tool/call" { return true }
    guard frame.type == "assistant/chunk", let chunk = chunk(frame) else { return false }
    return chunk.type == "text-delta" || chunk.type == "reasoning-delta" || chunk.type == "tool-call-delta"
}

private func chunk(_ frame: StreamFrame) -> ChunkFields? {
    guard let object = frame.data.objectValue, let chunk = object["chunk"]?.objectValue,
        let type = chunk["type"]?.stringValue
    else { return nil }
    let block = chunk["block"]?.objectValue ?? [:]
    return ChunkFields(
        type: type,
        turn: object["turn"]?.intValue ?? 0,
        index: chunk["index"]?.intValue ?? 0,
        text: chunk["text"]?.stringValue ?? block["text"]?.stringValue ?? "",
        arguments: chunk["argumentsDelta"]?.stringValue ?? "",
        name: chunk["name"]?.stringValue ?? "",
        callID: chunk["id"]?.stringValue ?? "",
        block: block)
}

private func userText(_ data: JSONValue) -> String {
    guard let content = data.objectValue?["content"]?.arrayValue else { return "" }
    return content.compactMap(\.objectValue?["text"]?.stringValue).joined()
}

private func argumentsText(_ value: JSONValue?) -> String? {
    switch value {
    case .string(let text): return text
    case .object, .array, .number, .bool:
        guard let value, let data = try? JSONEncoder().encode(value) else { return nil }
        return String(data: data, encoding: .utf8)
    default: return nil
    }
}

private func resultText(_ data: [String: JSONValue]) -> String {
    if let text = data["text"]?.stringValue { return text }
    guard let content = data["content"]?.arrayValue else { return "" }
    return content.compactMap { item -> String? in
        if let text = item.objectValue?["text"]?.stringValue { return text }
        if let data = try? JSONEncoder().encode(item) { return String(data: data, encoding: .utf8) }
        return nil
    }.joined(separator: "\n")
}

extension JSONValue {
    var objectValue: [String: JSONValue]? {
        if case .object(let value) = self { return value }
        return nil
    }

    var arrayValue: [JSONValue]? {
        if case .array(let value) = self { return value }
        return nil
    }

    var stringValue: String? {
        if case .string(let value) = self { return value }
        return nil
    }

    var intValue: Int? {
        switch self {
        case .number(let value): return Int(value)
        case .string(let value): return Int(value)
        default: return nil
        }
    }
}
