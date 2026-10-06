import Foundation

public enum AppVisibility: Equatable, Sendable {
    case active
    case inactive
    case background
}

public enum PrivacyCover {
    /// Hide the current page only after the scene leaves the foreground.
    public static func covers(_ visibility: AppVisibility, screenshots: Bool = false) -> Bool {
        screenshots ? false : visibility != .active
    }
}
