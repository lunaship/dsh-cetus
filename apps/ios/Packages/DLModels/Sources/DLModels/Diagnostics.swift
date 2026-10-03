import Foundation

/// `GET /dsh-link/mobile/diagnostics`（需 `capabilities.diagnostics.v >= 1`）。
/// `generatedAt` 是主机 Unix 秒，App 用它和本机时间算时钟偏差；文案由 App 按 `code` 本地化。
public struct DiagnosticsReport: Codable, Equatable, Sendable {
    public var version: Int?
    public var generatedAt: Int?
    public var checks: [DiagnosticCheck]?

    public init(version: Int? = nil, generatedAt: Int? = nil, checks: [DiagnosticCheck]? = nil) {
        self.version = version
        self.generatedAt = generatedAt
        self.checks = checks
    }
}

public struct DiagnosticCheck: Codable, Equatable, Sendable {
    /// 稳定 id：host.rpc / host.services / plugin.version / tls.cert / pairing.devices /
    /// remote.relay / clock（手机范围）。
    public var id: String?
    public var status: DiagnosticsStatus?
    /// 稳定错误码枚举（HOST_RPC_OK、TLS_CERT_EXPIRING、REMOTE_DISABLED…）。
    public var code: String?
    /// 只含数字、布尔和短枚举；不含 token、证书全文、绝对路径、IP 或消息正文。
    public var detail: [String: DiagnosticDetailValue]?

    public init(
        id: String? = nil,
        status: DiagnosticsStatus? = nil,
        code: String? = nil,
        detail: [String: DiagnosticDetailValue]? = nil
    ) {
        self.id = id
        self.status = status
        self.code = code
        self.detail = detail
    }
}

public enum DiagnosticsStatus: DLStringEnum {
    case ok
    case warn
    case fail
    case skip
    case unknown(String)

    public static func decoding(_ rawValue: String) -> Self {
        switch rawValue {
        case "ok": .ok
        case "warn": .warn
        case "fail": .fail
        case "skip": .skip
        default: .unknown(rawValue)
        }
    }

    public var encodedValue: String {
        switch self {
        case .ok: "ok"
        case .warn: "warn"
        case .fail: "fail"
        case .skip: "skip"
        case .unknown(let raw): raw
        }
    }
}

/// detail 值只会是数字、布尔或短枚举字符串（`src/diagnostics.js` 的 sanitizeDetail）。
public enum DiagnosticDetailValue: Codable, Equatable, Sendable {
    case number(Double)
    case flag(Bool)
    case text(String)

    public init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        // Double 先于 Bool，理由同 JSONValue。
        if let value = try? container.decode(Double.self) {
            self = .number(value)
            return
        }
        if let value = try? container.decode(Bool.self) {
            self = .flag(value)
            return
        }
        self = .text(try container.decode(String.self))
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .number(let value): try container.encode(value)
        case .flag(let value): try container.encode(value)
        case .text(let value): try container.encode(value)
        }
    }
}
