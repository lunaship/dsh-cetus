import CryptoKit
import Foundation

public struct PushRegistrationDraft: Equatable, Sendable {
    public var body: PushRegistrationBody
    public var contentKey: Data
    public var tokenFingerprint: String

    public init(body: PushRegistrationBody, contentKey: Data, tokenFingerprint: String) {
        self.body = body
        self.contentKey = contentKey
        self.tokenFingerprint = tokenFingerprint
    }
}

public enum PushRegistrar {
    public static let officialGateway = "https://push.dshlinks.com"
    public static let bundleID = "dev.deeplinks.ios"

    public static func gatewayOrigin(_ raw: String) -> String? {
        guard let url = URL(string: raw), let scheme = url.scheme?.lowercased(), scheme == "https" else {
            return nil
        }
        guard url.user == nil, url.password == nil, url.query == nil, url.fragment == nil else { return nil }
        guard let host = url.host, !host.isEmpty, url.path.isEmpty || url.path == "/" else { return nil }
        var origin = "https://\(host)"
        if let port = url.port { origin += ":\(port)" }
        return origin
    }

    public static func currentKey(in keys: [PushGatewayKey]) -> PushGatewayKey? {
        keys.last { !$0.kid.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.publicKey.count == 32 }
    }

    public static func prepare(
        token: PushDeviceToken,
        key: PushGatewayKey,
        gateway: String = officialGateway,
        bundleID: String = PushRegistrar.bundleID,
        prefs: PushPreferences,
        contentKey: SymmetricKey = SymmetricKey(size: .bits256),
        liveActivityToken: String? = nil
    ) -> PushRegistrationDraft? {
        guard let origin = gatewayOrigin(gateway), token.bytes.count == 32 else { return nil }
        let kid = key.kid.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !kid.isEmpty, key.publicKey.count == 32 else { return nil }
        let rawKey = contentKey.withUnsafeBytes { Data($0) }
        guard rawKey.count == 32 else { return nil }
        guard
            let plaintext = tokenPlaintext(
                token: token, bundleID: bundleID, liveActivityToken: liveActivityToken)
        else { return nil }
        guard let sealed = try? PushCrypto.sealToken(plaintext: plaintext, recipient: key.publicKey, kid: kid) else {
            return nil
        }
        let body = PushRegistrationBody(
            gateway: origin,
            kid: kid,
            sealed: PushTokenEnvelope(v: sealed.v, kid: sealed.kid, enc: sealed.enc, ct: sealed.ct),
            prefs: prefs)
        return PushRegistrationDraft(body: body, contentKey: rawKey, tokenFingerprint: fingerprint(token.bytes))
    }

    public static func fingerprint(_ token: Data) -> String {
        SHA256.hash(data: token).map { String(format: "%02x", $0) }.joined()
    }

    public static func fingerprint(_ sealed: PushTokenEnvelope) -> String {
        let canonical =
            "\(sealed.v)\n\(sealed.kid)\n\(sealed.enc)\n\(sealed.ct)"
        return fingerprint(Data(canonical.utf8))
    }

    private static func tokenPlaintext(
        token: PushDeviceToken, bundleID: String, liveActivityToken: String?
    ) -> Data? {
        var object: [String: String] = [
            "apnsToken": token.hex,
            "bundleId": bundleID,
            "env": token.environment.rawValue,
        ]
        if let liveActivityToken {
            let trimmed = liveActivityToken.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return nil }
            object["laToken"] = trimmed
        }
        return try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    }
}

public struct PushRegistrationController: Sendable {
    public var capabilities: LocalPushCapabilities
    public var enabled: Bool
    public var state: PushRegistrationState

    public init(capabilities: LocalPushCapabilities, enabled: Bool = false) {
        self.capabilities = capabilities
        self.enabled = false
        state = Self.resting(capabilities)
        _ = enabled
    }

    public mutating func setEnabled(_ value: Bool) -> PushRegistrationDraft? {
        guard capabilities.canEnable else {
            enabled = false
            state = Self.resting(capabilities)
            return nil
        }
        enabled = value
        state = .disabled
        return nil
    }

    public mutating func noteRegistered() {
        guard capabilities.canEnable, enabled else {
            state = Self.resting(capabilities)
            return
        }
        state = .registered
    }

    public mutating func noteUnregistered() {
        enabled = false
        state = capabilities.canEnable ? .disabled : Self.resting(capabilities)
    }

    public mutating func registrationFailed(_ failure: PushRegistrationFailure) {
        enabled = false
        switch failure {
        case .unavailable:
            state = .unavailable(capabilities.apnsBuildEnabled ? "主机没有推送能力" : "当前构建没有 APNs")
        case .missingCapability:
            state = .failed("主机没有推送能力")
        case .network:
            state = .failed("网络失败")
        case .certificateChanged:
            state = .failed("证书变化")
        case .rejected:
            state = .failed("注册被拒绝")
        }
    }

    private static func resting(_ capabilities: LocalPushCapabilities) -> PushRegistrationState {
        capabilities.canEnable
            ? .disabled
            : .unavailable(capabilities.apnsBuildEnabled ? "主机没有推送能力" : "当前构建没有 APNs")
    }
}
