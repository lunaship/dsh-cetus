import CryptoKit
import DLCore
import DLModels
import DLSecurity
import Foundation

/// Writes the redirected production files before the measured app process starts.
enum PerformanceFixtureWriter {
    static func prepare(at directory: URL) async throws {
        let marker = directory.appendingPathComponent("ready")
        if FileManager.default.fileExists(atPath: marker.path) { return }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let host = PairedHost(
            hostId: PerformanceFixtureConstants.hostID,
            name: PerformanceFixtureConstants.hostName,
            primaryUrl: "https://127.0.0.1:9",
            certFingerprint: String(repeating: "ab", count: 32),
            pairedAt: 1_728_000_000_000)
        try await HostStore(
            fileURL: directory.appendingPathComponent("paired-hosts.json"),
            secureStore: PerformanceFileSecureStore(
                directory: directory.appendingPathComponent("credentials", isDirectory: true))
        ).save(host: host, token: "performance-token")

        let inbox = directory.appendingPathComponent("inbox", isDirectory: true)
        try FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        let snapshot = PerformanceInboxFile(
            sessions: [
                SessionSummary(
                    sessionId: PerformanceFixtureConstants.sessionID,
                    title: PerformanceFixtureConstants.sessionTitle,
                    updatedAt: 1_728_000_000)
            ],
            archivedIDs: [], workspaces: [], hostName: PerformanceFixtureConstants.hostName,
            route: "local", eventsEnabled: false)
        let name =
            PerformanceFixtureConstants.hostID.addingPercentEncoding(
                withAllowedCharacters: .alphanumerics) ?? "host"
        try JSONEncoder().encode(snapshot).write(
            to: inbox.appendingPathComponent(name + ".json"), options: .atomic)

        let encoded = try TranscriptSnapshotCoding.encode(transcript)
        try TranscriptSnapshotBox(
            keys: PerformanceFileSecureStore(
                directory: directory.appendingPathComponent("credentials", isDirectory: true)),
            directory: directory.appendingPathComponent("snapshots", isDirectory: true)
        ).write(
            hostID: PerformanceFixtureConstants.hostID,
            sessionID: PerformanceFixtureConstants.sessionID,
            plaintext: encoded)
        try Data("ready".utf8).write(to: marker, options: .atomic)
    }

    private static var transcript: TranscriptSnapshotRecord {
        let count = PerformanceFixtureConstants.messageCount
        let messages = (1...count).map { index in
            HistoryMessage(
                id: "message-\(index)", seq: index,
                role: index.isMultiple(of: 2) ? "assistant" : "user",
                kind: index.isMultiple(of: 2) ? .role("assistant") : .user,
                text: "固定消息 \(index)")
        }
        return TranscriptSnapshotRecord(
            messages: messages, stats: nil, maxSeq: count,
            title: PerformanceFixtureConstants.sessionTitle, workspace: "",
            running: false, step: nil)
    }
}

private struct PerformanceInboxFile: Encodable {
    var sessions: [SessionSummary]
    var archivedIDs: [String]
    var workspaces: [WorkspaceInfo]
    var hostName: String
    var route: String
    var eventsEnabled: Bool
}
