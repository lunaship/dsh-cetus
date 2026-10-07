import Foundation

/// Values shared by the measured app and the UI-test writer.
/// Nothing here reads the developer’s real hosts, credentials, or keychain.
enum PerformanceFixtureConstants {
    static let argument = "-performanceFixture"
    static let unsignedStorageArgument = "-performanceUnsignedStorage"
    static let hostID = "host-performance"
    static let hostName = "性能电脑"
    static let sessionID = "session-performance"
    static let sessionTitle = "性能会话"
    static let messageCount = 3000
    static let secureService = "dev.deeplinks.ios.performance"
}
