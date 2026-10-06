import Foundation

/// 字符串枚举的通用解码兜底：已知取值在 `decoding(_:)` 里映射到具体 case，
/// 未知取值必须落到 `.unknown(String)`，保证插件新增枚举值时旧 App 不因解码失败丢整条响应。
/// 编码时透传原始字符串（unknown 原样写回）。
public protocol DLStringEnum: Codable, Hashable, Sendable {
    static func decoding(_ rawValue: String) -> Self
    var encodedValue: String { get }
}

extension DLStringEnum {
    public init(from decoder: any Decoder) throws {
        self = Self.decoding(try decoder.singleValueContainer().decode(String.self))
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(encodedValue)
    }
}

/// 合同里未定形状的 JSON 值（SSE 事件 data、prompt result、未建模投影等）的占位类型。
public enum JSONValue: Codable, Equatable, Sendable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
            return
        }
        // Double 先于 Bool：部分平台对 0/1 的 Bool 解码有历史宽容行为，数字优先落 number。
        if let value = try? container.decode(Double.self) {
            self = .number(value)
            return
        }
        if let value = try? container.decode(Bool.self) {
            self = .bool(value)
            return
        }
        if let value = try? container.decode(String.self) {
            self = .string(value)
            return
        }
        if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
            return
        }
        self = .object(try container.decode([String: JSONValue].self))
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null: try container.encodeNil()
        case .bool(let value): try container.encode(value)
        case .number(let value): try container.encode(value)
        case .string(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .object(let value): try container.encode(value)
        }
    }
}

/// 解码时截下原始 JSON 片段。数字、键序和未知字段都保持字节级原文，不经模型重编码。
struct RawJSON: Decodable {
    var text: String

    init(from decoder: any Decoder) throws {
        guard let capture = decoder.userInfo[RawJSON.userInfoKey] as? RawJSONCapture else {
            throw DecodingError.dataCorrupted(
                .init(codingPath: decoder.codingPath, debugDescription: "raw JSON capture is unavailable"))
        }
        text = try capture.slice(at: decoder.codingPath)
    }

    static let userInfoKey = CodingUserInfoKey(rawValue: "dev.deeplinks.raw-json")!
}

/// 给 `JSONDecoder.userInfo` 用：按 coding path 从同一份原文切出片段。
public struct RawJSONCapture: Sendable {
    private let data: Data

    public init(_ data: Data) {
        self.data = data
    }

    func slice(at path: [any CodingKey]) throws -> String {
        var value = try JSONValueParser(data).parseValue(requireComplete: true)
        for key in path {
            if let index = key.intValue {
                guard case .array(let items, _) = value, items.indices.contains(index) else {
                    throw RawJSONError.missing
                }
                value = items[index]
            } else {
                guard case .object(let fields, _) = value, let next = fields[key.stringValue] else {
                    throw RawJSONError.missing
                }
                value = next
            }
        }
        let bytes = data.subdata(in: value.range)
        guard let text = String(data: bytes, encoding: .utf8) else { throw RawJSONError.missing }
        return text
    }
}

private enum RawJSONError: Error {
    case missing
    case truncated
}

private struct RawSpan {
    var range: Range<Data.Index>
}

private enum RawNode {
    case scalar(RawSpan)
    case array([RawNode], RawSpan)
    case object([String: RawNode], RawSpan)

    var range: Range<Data.Index> {
        switch self {
        case .scalar(let span), .array(_, let span), .object(_, let span): span.range
        }
    }
}

private struct JSONValueParser {
    private let bytes: [UInt8]
    private var index = 0

    init(_ data: Data) {
        bytes = [UInt8](data)
    }

    func parseValue(requireComplete: Bool) throws -> RawNode {
        var parser = self
        let value = try parser.parseValue()
        if requireComplete {
            parser.skipWhitespace()
            if parser.index != parser.bytes.count { throw RawJSONError.truncated }
        }
        return value
    }

    private mutating func parseValue() throws -> RawNode {
        skipWhitespace()
        guard let byte = peek() else { throw RawJSONError.truncated }
        switch byte {
        case 0x7B: return try parseObject()
        case 0x5B: return try parseArray()
        case 0x22: return .scalar(try parseString())
        default: return .scalar(try parseLiteral())
        }
    }

    private mutating func parseObject() throws -> RawNode {
        let start = index
        try consume(0x7B)
        var fields: [String: RawNode] = [:]
        skipWhitespace()
        if consumeIf(0x7D) { return .object(fields, span(from: start)) }
        while true {
            skipWhitespace()
            let keySpan = try parseString()
            let key = try text(keySpan).unescaped()
            skipWhitespace()
            try consume(0x3A)
            fields[key] = try parseValue()
            skipWhitespace()
            if consumeIf(0x7D) { return .object(fields, span(from: start)) }
            try consume(0x2C)
        }
    }

    private mutating func parseArray() throws -> RawNode {
        let start = index
        try consume(0x5B)
        var items: [RawNode] = []
        skipWhitespace()
        if consumeIf(0x5D) { return .array(items, span(from: start)) }
        while true {
            items.append(try parseValue())
            skipWhitespace()
            if consumeIf(0x5D) { return .array(items, span(from: start)) }
            try consume(0x2C)
        }
    }

    private mutating func parseString() throws -> RawSpan {
        let start = index
        try consume(0x22)
        while let byte = peek() {
            advance()
            if byte == 0x22 { return span(from: start) }
            if byte == 0x5C {
                guard peek() != nil else { throw RawJSONError.truncated }
                advance()
            }
        }
        throw RawJSONError.truncated
    }

    private mutating func parseLiteral() throws -> RawSpan {
        let start = index
        while let byte = peek(), !isStructural(byte), !isWhitespace(byte) { advance() }
        if index == start { throw RawJSONError.truncated }
        return span(from: start)
    }

    private func text(_ span: RawSpan) throws -> String {
        let raw = Data(bytes[span.range]).dropFirst().dropLast()
        guard let text = String(data: raw, encoding: .utf8) else { throw RawJSONError.missing }
        return text
    }

    private func span(from start: Int) -> RawSpan {
        RawSpan(range: start..<index)
    }

    private mutating func skipWhitespace() {
        while let byte = peek(), isWhitespace(byte) { advance() }
    }

    private func isWhitespace(_ byte: UInt8) -> Bool {
        byte == 0x20 || byte == 0x09 || byte == 0x0A || byte == 0x0D
    }

    private func isStructural(_ byte: UInt8) -> Bool {
        byte == 0x7B || byte == 0x7D || byte == 0x5B || byte == 0x5D || byte == 0x3A || byte == 0x2C
    }

    private func peek() -> UInt8? {
        index < bytes.count ? bytes[index] : nil
    }

    private mutating func advance() {
        index += 1
    }

    private mutating func consume(_ expected: UInt8) throws {
        guard peek() == expected else { throw RawJSONError.truncated }
        advance()
    }

    private mutating func consumeIf(_ expected: UInt8) -> Bool {
        guard peek() == expected else { return false }
        advance()
        return true
    }
}

extension String {
    fileprivate func unescaped() throws -> String {
        let wrapped = "\"" + self + "\""
        guard let data = wrapped.data(using: .utf8),
            let value = try? JSONDecoder().decode(String.self, from: data)
        else { throw RawJSONError.missing }
        return value
    }
}

public extension JSONDecoder {
    /// 让模型里的 `questionsJSON` 能取到同一份响应原文。没有原文时该字段保持空。
    @discardableResult
    func preservingRawJSON(_ data: Data) -> JSONDecoder {
        userInfo[RawJSON.userInfoKey] = RawJSONCapture(data)
        return self
    }

    /// 解码并保留原文片段。提问快照和提问事件用它，不改其他响应的解码路径。
    func decodePreservingRawJSON<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        preservingRawJSON(data)
        return try decode(type, from: data)
    }
}

/// bootstrap 里 `remote` 的三态语义（合同「远程能力（DLP/1）」）：
/// 键缺失（旧插件）= 不动；`null` = 远程已停用，清远程字段、保留局域网配对；对象 = 更新远程能力。
/// 缺失与 null 必须区分，因此由 `BootstrapResponse` 的自定义解码负责落 case，本枚举自身不做 Codable。
public enum RemoteAvailability: Equatable, Sendable {
    case absent
    case disabled
    case enabled(DeviceRemoteInfo)
}
