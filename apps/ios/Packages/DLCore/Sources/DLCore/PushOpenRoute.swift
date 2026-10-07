import Foundation

public enum PushOpenRoute: Equatable, Sendable {
    case session(String)
    case refresh
    case homeMissing
}

public enum PushOpenRouter {
    public static func route(
        deviceID: String,
        sessionID: String,
        pairedDeviceID: String?,
        knownSessions: Set<String>,
        refreshed: Bool = false
    ) -> PushOpenRoute {
        guard pairedDeviceID == deviceID else { return .homeMissing }
        if knownSessions.contains(sessionID) { return .session(sessionID) }
        return refreshed ? .homeMissing : .refresh
    }
}
