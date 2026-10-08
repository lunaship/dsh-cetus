import CryptoKit
import Foundation

public enum PushNotificationKind: String, Equatable, Sendable {
    case approval
    case question
    case completed
    case failed
}

public struct PushNotificationPayload: Equatable, Sendable {
    public var kind: PushNotificationKind
    public var sessionID: String
    public var title: String
    public var deviceID: String
    public var timestamp: Int

    public init(kind: PushNotificationKind, sessionID: String, title: String, deviceID: String, timestamp: Int) {
        self.kind = kind
        self.sessionID = sessionID
        self.title = title
        self.deviceID = deviceID
        self.timestamp = timestamp
    }
}

public enum PushContent {
    public static let generic = "cetus 有新的任务动态"
    public static let maxAge: TimeInterval = 15 * 60

    public static func open(ciphertext: String, key: Data, deviceID: String, now: Date = Date()) -> String {
        openPayload(ciphertext: ciphertext, key: key, deviceID: deviceID, now: now)?.title ?? generic
    }

    /// The exact function used by the notification extension. It never opens a
    /// connection and never reads an APNs device token.
    public static func openPayload(
        ciphertext: String, key: Data, deviceID: String, now: Date = Date()
    ) -> PushNotificationPayload? {
        guard key.count == 32, let raw = Data(base64Encoded: ciphertext), raw.count > 28 else { return nil }
        let plaintext: Data
        do {
            let box = try AES.GCM.SealedBox(combined: raw)
            plaintext = try AES.GCM.open(
                box, using: SymmetricKey(data: key), authenticating: PushCrypto.contentAAD(deviceID: deviceID))
        } catch {
            return nil
        }
        guard let object = try? JSONSerialization.jsonObject(with: plaintext) as? [String: Any],
            let kind = (object["type"] as? String).flatMap(PushNotificationKind.init(rawValue:)),
            let sessionID = object["sessionId"] as? String, !sessionID.isEmpty,
            let title = object["title"] as? String, !title.isEmpty,
            let timestamp = integer(object["ts"])
        else { return nil }
        guard abs(now.timeIntervalSince1970 - TimeInterval(timestamp)) <= maxAge else { return nil }
        return PushNotificationPayload(
            kind: kind, sessionID: sessionID, title: title, deviceID: deviceID, timestamp: timestamp)
    }

    private static func integer(_ value: Any?) -> Int? {
        if let value = value as? Int { return value }
        if let value = value as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID() { return value.intValue }
        return nil
    }
}

public enum PushNotificationCategory {
    public static let identifier = "dlpush.open"
    public static let openAction = "dlpush.open.action"
}

public struct PushOpenRequest: Equatable, Sendable {
    public var deviceID: String
    public var sessionID: String

    public init(deviceID: String, sessionID: String) {
        self.deviceID = deviceID
        self.sessionID = sessionID
    }
}

public enum PushPayloadReader {
    /// Notification taps carry only routing identifiers. They never approve and
    /// never expose the encrypted body to navigation.
    ///
    /// The APNs payload has neither `deviceId` nor `sessionId` (RFC 0002 §5.6
    /// sends only `aps`, `e`, `k`), so both are recovered locally: `deviceId`
    /// from the `kid` binding, `sessionId` by opening the ciphertext with the
    /// shared key. The decrypted `sessionId` is used for navigation only —
    /// never to approve.
    public static func openRequest(
        in userInfo: [AnyHashable: Any], bindings: PushKeyStore? = nil, now: Date = Date()
    ) -> PushOpenRequest? {
        let deviceID = deviceID(in: userInfo, bindings: bindings) ?? ""
        let sessionID =
            string("sessionId", in: userInfo)
            ?? decryptedSessionID(in: userInfo, bindings: bindings, now: now)
        guard let sessionID, !sessionID.isEmpty else { return nil }
        return PushOpenRequest(deviceID: deviceID, sessionID: sessionID)
    }

    /// Opens the payload just far enough to recover the session id for routing.
    ///
    /// The freshness rule still applies: a stale notification must not route to
    /// a session either, since the request it referred to is long gone.
    private static func decryptedSessionID(
        in userInfo: [AnyHashable: Any], bindings: PushKeyStore?, now: Date
    ) -> String? {
        guard let bindings else { return nil }
        let ciphertext = ciphertext(in: userInfo)
        guard !ciphertext.isEmpty else { return nil }
        let deviceID = deviceID(in: userInfo, bindings: bindings) ?? ""
        guard !deviceID.isEmpty, let key = (try? bindings.load()) ?? nil else { return nil }
        return PushContent.openPayload(ciphertext: ciphertext, key: key, deviceID: deviceID, now: now)?.sessionID
    }

    public static func ciphertext(in userInfo: [AnyHashable: Any]) -> String {
        if let value = userInfo["e"] as? String, !value.isEmpty { return value }
        if let value = userInfo["ct"] as? String, !value.isEmpty { return value }
        return ""
    }

    /// The gateway key id that travels in the APNs payload (`k`). The extension
    /// resolves the matching `deviceId` locally through `PushKeyStore`.
    public static func kid(in userInfo: [AnyHashable: Any]) -> String {
        string("k", in: userInfo) ?? ""
    }

    /// Resolves the device id to rebuild the content AAD from.
    ///
    /// RFC 0002 keeps `deviceId` out of the APNs payload, so it is looked up
    /// from the shared binding written at registration. An explicit `deviceId`
    /// in the payload still wins when a future build sends one. Returning nil
    /// (unknown `kid`, never registered) makes the caller show the generic
    /// notification instead of guessing an AAD that would fail to open.
    public static func deviceID(
        in userInfo: [AnyHashable: Any], bindings: PushKeyStore? = nil
    ) -> String? {
        if let explicit = string("deviceId", in: userInfo) { return explicit }
        let kid = kid(in: userInfo)
        guard !kid.isEmpty, let bindings else { return nil }
        return (try? bindings.deviceID(forKid: kid)) ?? nil
    }

    private static func string(_ key: String, in userInfo: [AnyHashable: Any]) -> String? {
        guard let value = userInfo[key] as? String else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
