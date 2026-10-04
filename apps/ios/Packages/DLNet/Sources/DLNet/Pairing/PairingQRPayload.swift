import CoreFoundation
import DLModels
import Foundation

public enum PairingQRParseError: Error, Equatable, Sendable {
    case notDeepLinks
    case incomplete
    case invalidTimestamp(field: String)
}

/// Android `PairingQr.kt` 的载荷语义；只保存 remote 原始字段，阶段 5 才派生密钥或连接中继。
public struct PairingQRPayload: Equatable, Sendable {
    public let code: String
    public let urls: [String]
    public let name: String
    public let certFingerprint: String
    public let issuedAt: Int?
    public let expiresAt: Int?
    public let remote: QrRemoteInfo?

    public init(_ text: String) throws {
        guard let payload = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
            Self.optString(payload["type"]) == "dsh-link"
        else { throw PairingQRParseError.notDeepLinks }
        // optString 的兼容规则：pairingCode 优先，缺键 / null 才回退 code；数字亦可转字符串。
        code = Self.optString(payload["pairingCode"], fallback: Self.optString(payload["code"]))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let rawURLs = payload["urls"] as? [String]
        let trimmedURLs = rawURLs?.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        urls = trimmedURLs?.allSatisfy({ !$0.isEmpty }) == true ? trimmedURLs! : []
        let rawName = Self.optString(payload["name"], fallback: "dsh")
        name = rawName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "dsh" : rawName
        certFingerprint = Self.optString(payload["certFingerprint"]).trimmingCharacters(in: .whitespacesAndNewlines)
        remote = certFingerprint.isEmpty ? nil : Self.parseRemote(payload["remote"])
        guard !code.isEmpty, !urls.isEmpty || remote != nil else { throw PairingQRParseError.incomplete }
        issuedAt = try Self.timestamp(payload["issuedAt"], field: "issuedAt")
        expiresAt = try Self.timestamp(payload["expiresAt"], field: "expiresAt")
        // v / requireConfirm 不决定手机配对行为；旧 relay 与未知字段同 Android 一样忽略。
    }

    private static func optString(_ value: Any?, fallback: String = "") -> String {
        guard let value, !(value is NSNull) else { return fallback }
        if let string = value as? String { return string }
        guard let data = try? JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed, .sortedKeys])
        else {
            return fallback
        }
        return String(decoding: data, as: UTF8.self)
    }

    private static func timestamp(_ value: Any?, field: String) throws -> Int? {
        guard let value, !(value is NSNull) else { return nil }
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
            let integer = Int(number.stringValue)
        else { throw PairingQRParseError.invalidTimestamp(field: field) }
        return integer
    }

    private static func parseRemote(_ value: Any?) -> QrRemoteInfo? {
        guard let object = value as? [String: Any],
            let data = try? JSONSerialization.data(withJSONObject: object),
            let remote = try? JSONDecoder().decode(QrRemoteInfo.self, from: data),
            let endpoint = remote.endpoint, let url = URLComponents(string: endpoint),
            url.scheme == "wss", let host = url.host, !host.isEmpty,
            validBase64URL(remote.routeId, bytes: 16), validBase64URL(remote.bootstrapSeed, bytes: 16)
        else { return nil }
        if let pin = remote.outerCertificatePin,
            pin.count != 64 || !pin.allSatisfy({ "0123456789abcdef".contains($0) })
        {
            return nil
        }
        return remote
    }

    /// Android RemoteRoute.parseDeviceRemote：可选能力写坏只丢 remote，不影响 LAN 凭据。
    static func validatedDeviceRemote(_ remote: DeviceRemoteInfo?) -> DeviceRemoteInfo? {
        guard let remote, let endpoint = remote.endpoint, let url = URLComponents(string: endpoint),
            url.scheme == "wss", let host = url.host, !host.isEmpty,
            validBase64URL(remote.routeId, bytes: 16), validBase64URL(remote.deviceHandle, bytes: 16),
            validBase64URL(remote.relayKey, bytes: 32)
        else { return nil }
        if let pin = remote.outerCertificatePin,
            pin.count != 64 || !pin.allSatisfy({ "0123456789abcdef".contains($0) })
        {
            return nil
        }
        return remote
    }

    private static func validBase64URL(_ value: String?, bytes: Int) -> Bool {
        guard let value, !value.isEmpty, !value.contains("="),
            value.allSatisfy({ "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".contains($0) })
        else { return false }
        let padded =
            value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
            + String(repeating: "=", count: (4 - value.count % 4) % 4)
        guard let decoded = Data(base64Encoded: padded), decoded.count == bytes else { return false }
        let canonical = decoded.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        return canonical == value
    }
}
