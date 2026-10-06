import CryptoKit
import Foundation

public struct LocalPushCapabilities: Equatable, Sendable {
    public var version: Int
    public var apnsBuildEnabled: Bool

    public init(version: Int = 0, apnsBuildEnabled: Bool = false) {
        self.version = version
        self.apnsBuildEnabled = apnsBuildEnabled
    }

    public var canEnable: Bool { version == 1 && apnsBuildEnabled }
}

public enum PushRegistrationState: Equatable, Sendable {
    case unavailable(String)
    case disabled
    case failed(String)
    case registered
}

public struct PushRegistrationRequest: Equatable, Sendable {
    public var gateway: String
    public var kid: String
    public var tokenEnvelope: String
    public var contentKey: Data
    public var prefs: [String: Bool]

    public init(gateway: String, kid: String, tokenEnvelope: String, contentKey: Data, prefs: [String: Bool]) {
        self.gateway = gateway
        self.kid = kid
        self.tokenEnvelope = tokenEnvelope
        self.contentKey = contentKey
        self.prefs = prefs
    }
}

public struct PushRegistrationController: Sendable {
    public var capabilities: LocalPushCapabilities
    public var enabled: Bool
    public var state: PushRegistrationState

    public init(capabilities: LocalPushCapabilities, enabled: Bool = false) {
        self.capabilities = capabilities
        self.enabled = capabilities.canEnable && enabled
        state =
            capabilities.canEnable
            ? (self.enabled ? .registered : .disabled)
            : .unavailable(capabilities.apnsBuildEnabled ? "主机没有推送能力" : "当前构建没有 APNs")
    }

    public mutating func setEnabled(_ value: Bool) -> PushRegistrationRequest? {
        guard capabilities.canEnable else {
            enabled = false
            state = .unavailable(capabilities.apnsBuildEnabled ? "主机没有推送能力" : "当前构建没有 APNs")
            return nil
        }
        enabled = value
        guard value else {
            state = .disabled
            return nil
        }
        let key = SymmetricKey(size: .bits256)
        state = .registered
        return PushRegistrationRequest(
            gateway: "https://push.dshlinks.com",
            kid: "local",
            tokenEnvelope: "local-envelope",
            contentKey: key.withUnsafeBytes { Data($0) },
            prefs: ["approval": true, "question": true, "completed": true, "failed": true]
        )
    }

    public mutating func registrationFailed(_ message: String) {
        enabled = false
        state = .failed(message)
    }
}

public enum PushContent {
    public static func open(ciphertext: String, key: Data, deviceID: String, now: Date = Date()) -> String {
        guard let raw = Data(base64Encoded: ciphertext), raw.count > 28 else { return "DeepLinks 有新的任务动态" }
        do {
            let box = try AES.GCM.SealedBox(combined: raw)
            let plaintext = try AES.GCM.open(
                box, using: SymmetricKey(data: key), authenticating: Data(("dlpush/1 content|" + deviceID).utf8))
            let object = try JSONSerialization.jsonObject(with: plaintext) as? [String: Any]
            let ts = object?["ts"] as? Int ?? 0
            guard abs(now.timeIntervalSince1970 - TimeInterval(ts)) <= 15 * 60 else { return "DeepLinks 有新的任务动态" }
            return (object?["title"] as? String)?.isEmpty == false
                ? object?["title"] as? String ?? "DeepLinks 有新的任务动态" : "DeepLinks 有新的任务动态"
        } catch {
            return "DeepLinks 有新的任务动态"
        }
    }
}
