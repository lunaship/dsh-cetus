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

/// bootstrap 里 `remote` 的三态语义（合同「远程能力（DLP/1）」）：
/// 键缺失（旧插件）= 不动；`null` = 远程已停用，清远程字段、保留局域网配对；对象 = 更新远程能力。
/// 缺失与 null 必须区分，因此由 `BootstrapResponse` 的自定义解码负责落 case，本枚举自身不做 Codable。
public enum RemoteAvailability: Equatable, Sendable {
    case absent
    case disabled
    case enabled(DeviceRemoteInfo)
}
