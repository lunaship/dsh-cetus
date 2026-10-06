import Foundation

public enum PushOpenRoute: Equatable, Sendable {
    case session(String)
    case homeMissing
}

public enum PushOpenRouter {
    public static func route(deviceID: String, sessionID: String, pairedDeviceID: String?, knownSessions: Set<String>)
        -> PushOpenRoute
    {
        guard pairedDeviceID == deviceID, knownSessions.contains(sessionID) else {
            return .homeMissing
        }
        return .session(sessionID)
    }
}
