import Foundation

/// What the phone may turn on. Both the host capability and an APNs-capable
/// build are required; a free-signed build has neither an entitlement nor a token.
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

public enum PushEnvironment: String, Equatable, Sendable {
    case sandbox
    case production
}

/// RFC 0002 section 5.3 sealed object. It is JSON, never a serialized string.
public struct PushTokenEnvelope: Equatable, Sendable, Codable {
    public var v: Int
    public var kid: String
    public var enc: String
    public var ct: String

    public init(v: Int, kid: String, enc: String, ct: String) {
        self.v = v
        self.kid = kid
        self.enc = enc
        self.ct = ct
    }
}

public struct PushPreferences: Equatable, Sendable, Codable {
    public var approval: Bool
    public var question: Bool
    public var completed: Bool
    public var failed: Bool

    public init(approval: Bool, question: Bool, completed: Bool, failed: Bool) {
        self.approval = approval
        self.question = question
        self.completed = completed
        self.failed = failed
    }
}

/// One gateway public key from GET /v1/keys. The raw key is 32 bytes.
public struct PushGatewayKey: Equatable, Sendable {
    public var kid: String
    public var publicKey: Data

    public init(kid: String, publicKey: Data) {
        self.kid = kid
        self.publicKey = publicKey
    }
}

public struct PushDeviceToken: Equatable, Sendable {
    public var bytes: Data
    public var environment: PushEnvironment

    public init(bytes: Data, environment: PushEnvironment) {
        self.bytes = bytes
        self.environment = environment
    }

    public var hex: String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }
}

/// Plugin registration fields that may be retained locally. The content key is
/// not one of them: it travels once as the plugin field `k` and otherwise lives
/// only in `PushKeyStore`.
public struct PushRegistrationBody: Equatable, Sendable, Encodable {
    public var gateway: String
    public var kid: String
    public var sealed: PushTokenEnvelope
    public var prefs: PushPreferences

    public init(gateway: String, kid: String, sealed: PushTokenEnvelope, prefs: PushPreferences) {
        self.gateway = gateway
        self.kid = kid
        self.sealed = sealed
        self.prefs = prefs
    }
}

public enum PushRegistrationFailure: Equatable, Sendable {
    case unavailable
    case missingCapability
    case network
    case certificateChanged
    case rejected
}

/// Non-secret reconciliation metadata. The APNs token, sealed object, and content
/// key are intentionally absent: the token is sealed again for each registration,
/// and the content key lives only in `PushKeyStore`.
public struct PushRegistrationRecord: Equatable, Sendable, Codable {
    public var gateway: String
    public var kid: String
    public var sealedFingerprint: String
    public var tokenFingerprint: String
    public var prefs: PushPreferences

    public init(
        gateway: String,
        kid: String,
        sealedFingerprint: String,
        tokenFingerprint: String,
        prefs: PushPreferences
    ) {
        self.gateway = gateway
        self.kid = kid
        self.sealedFingerprint = sealedFingerprint
        self.tokenFingerprint = tokenFingerprint
        self.prefs = prefs
    }
}

public enum PushReconcileAction: Equatable, Sendable {
    case none
    case register(PushRegistrationBody)
    case unregister
}

/// Decides whether a stored registration still matches the current token,
/// gateway key, preferences, and switch. It never stores the content key.
public enum PushRegistrationReconciler {
    public static func action(
        enabled: Bool,
        canRegister: Bool,
        stored: PushRegistrationRecord?,
        prepared: PushRegistrationBody?
    ) -> PushReconcileAction {
        guard enabled, canRegister, let prepared else {
            return stored == nil ? .none : .unregister
        }
        guard let stored else { return .register(prepared) }
        if stored.gateway == prepared.gateway, stored.kid == prepared.kid,
            stored.sealedFingerprint == PushRegistrar.fingerprint(prepared.sealed),
            stored.prefs == prepared.prefs
        {
            return .none
        }
        return .register(prepared)
    }
}

public enum PushRegistrationWire {
    public static func json(_ body: PushRegistrationBody, contentKey: Data) throws -> Data {
        guard contentKey.count == 32 else { throw PushRegistrationWireError.invalidKey }
        let object: [String: Any] = [
            "gateway": body.gateway,
            "kid": body.kid,
            "sealed": [
                "v": body.sealed.v,
                "kid": body.sealed.kid,
                "enc": body.sealed.enc,
                "ct": body.sealed.ct,
            ],
            "k": contentKey.map { String(format: "%02x", $0) }.joined(),
            "prefs": [
                "approval": body.prefs.approval,
                "question": body.prefs.question,
                "completed": body.prefs.completed,
                "failed": body.prefs.failed,
            ],
        ]
        return try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    }
}

public enum PushRegistrationWireError: Error, Equatable {
    case invalidKey
}
