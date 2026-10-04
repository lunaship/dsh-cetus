import DLCore
import DLModels
import DLSecurity
import Foundation
import Testing

@Suite struct TranscriptSnapshotTests {
    @Test func roundTripAndIsolation() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
            UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let keys = InMemorySecureStore()
        let box = TranscriptSnapshotBox(keys: keys, directory: directory)
        #expect(try box.read(hostID: "host-a", sessionID: "missing") == nil)
        #expect(try keys.allKeys().isEmpty)

        let record = TranscriptSnapshotRecord(
            messages: [HistoryMessage(id: "m", role: "user", kind: .user, text: "你好")],
            stats: nil, maxSeq: 4, title: "发布说明", workspace: "/work/app", running: true, step: 2)
        try box.write(hostID: "host-a", sessionID: "session", plaintext: try TranscriptSnapshotCoding.encode(record))
        let plaintext = try box.read(hostID: "host-a", sessionID: "session")
        let decoded = try TranscriptSnapshotCoding.decode(try #require(plaintext))
        #expect(decoded == record)

        let copied = box.fileURL(hostID: "host-b", sessionID: "session")
        try FileManager.default.createDirectory(
            at: copied.deletingLastPathComponent(), withIntermediateDirectories: true)
        try FileManager.default.copyItem(at: box.fileURL(hostID: "host-a", sessionID: "session"), to: copied)
        #expect(throws: TranscriptSnapshotBox.Failure.unreadable) {
            try box.read(hostID: "host-b", sessionID: "session")
        }

        let original = box.fileURL(hostID: "host-a", sessionID: "session")
        var bytes = try Data(contentsOf: original)
        bytes[bytes.count / 2] ^= 0xFF
        try bytes.write(to: original, options: .atomic)
        #expect(throws: TranscriptSnapshotBox.Failure.unreadable) {
            try box.read(hostID: "host-a", sessionID: "session")
        }
    }
}
