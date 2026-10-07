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
    public static func openRequest(in userInfo: [AnyHashable: Any]) -> PushOpenRequest? {
        guard let deviceID = string("deviceId", in: userInfo),
            let sessionID = string("sessionId", in: userInfo)
        else { return nil }
        return PushOpenRequest(deviceID: deviceID, sessionID: sessionID)
    }

    public static func ciphertext(in userInfo: [AnyHashable: Any]) -> String {
        if let value = userInfo["e"] as? String, !value.isEmpty { return value }
        if let value = userInfo["ct"] as? String, !value.isEmpty { return value }
        return ""
    }

    public static func deviceID(in userInfo: [AnyHashable: Any]) -> String {
        string("deviceId", in: userInfo) ?? ""
    }

    private static func string(_ key: String, in userInfo: [AnyHashable: Any]) -> String? {
        guard let value = userInfo[key] as? String else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
