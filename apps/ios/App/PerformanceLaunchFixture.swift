import DLCore
import DLSecurity
import Foundation

/// Performance fixtures only. A normal launch has no argument and ignores this storage.
/// The UI-test host writes the files before measuring the app process.
enum PerformanceLaunchFixture {
    static let argument = PerformanceFixtureConstants.argument
    static let hostID = PerformanceFixtureConstants.hostID
    static let hostName = PerformanceFixtureConstants.hostName
    static let sessionID = PerformanceFixtureConstants.sessionID
    static let sessionTitle = PerformanceFixtureConstants.sessionTitle
    static let secureService = PerformanceFixtureConstants.secureService

    static var isRequested: Bool { directory != nil }

    static var unsignedStorage: URL? {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: PerformanceFixtureConstants.unsignedStorageArgument),
            index + 1 < arguments.count
        else { return nil }
        let path = arguments[index + 1]
        guard !path.isEmpty else { return nil }
        return URL(fileURLWithPath: path, isDirectory: true)
    }

    static var directory: URL? {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: argument), index + 1 < arguments.count else { return nil }
        let path = arguments[index + 1]
        guard !path.isEmpty else { return nil }
        return URL(fileURLWithPath: path, isDirectory: true)
    }

    static func hostStore() -> HostStore {
        if let directory {
            return hostStore(in: directory)
        }
        guard let unsignedStorage else {
            return HostStore()
        }
        return hostStore(in: unsignedStorage)
    }

    static func inboxDirectory() -> URL {
        requiredDirectory.appendingPathComponent("inbox", isDirectory: true)
    }

    static func snapshotBox() -> TranscriptSnapshotBox {
        snapshotBox(in: requiredDirectory)
    }

    private static func hostStore(in directory: URL) -> HostStore {
        HostStore(
            fileURL: directory.appendingPathComponent("paired-hosts.json"),
            secureStore: PerformanceFileSecureStore(
                directory: directory.appendingPathComponent("credentials", isDirectory: true)))
    }

    private static func snapshotBox(in directory: URL) -> TranscriptSnapshotBox {
        TranscriptSnapshotBox(
            keys: PerformanceFileSecureStore(
                directory: directory.appendingPathComponent("credentials", isDirectory: true)),
            directory: directory.appendingPathComponent("snapshots", isDirectory: true))
    }

    private static var requiredDirectory: URL {
        directory ?? URL(fileURLWithPath: "/dev/null", isDirectory: true)
    }
}
