import DLCore
import DLModels
import Foundation
import Testing

struct ShareInboxTests {
    @Test("分享链接只接受安全 id，选择后只生成草稿")
    func shareLinkAndPrefillStayLocal() throws {
        let record = try ShareInbox.textRecord(
            text: "  color is inverted  ", id: "abcd1234", now: Date(timeIntervalSince1970: 0)
        ).get()
        let url = try #require(ShareInbox.openURL(id: record.id))
        #expect(ShareInbox.id(from: url) == "abcd1234")
        #expect(ShareInbox.id(from: URL(string: "deeplinks://share/../secret")!) == nil)
        #expect(ShareInbox.textRecord(text: "   ", id: "abcd1234").isFailure)

        let prefill = ShareInbox.prefill(record: record, image: nil, target: .session("s1"))
        #expect(prefill == SharePrefill(target: .session("s1"), text: "color is inverted"))
        #expect(prefill.images.isEmpty)
    }

    @Test("图片只存在 App Group，路径不能逃出目录")
    func imageRecordStoresBytesOnlyInsideGroup() throws {
        let record = try ShareInbox.imageRecord(
            bytes: Data("png!".utf8), text: "按钮颜色反了", id: "abcd1234", now: Date(timeIntervalSince1970: 0)
        ).get()
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(
            UUID().uuidString, isDirectory: true)
        let store = ShareGroupStore(root: root)
        try store.writeRecord(record, image: Data("png!".utf8))
        try store.writeRecents([
            ShareRecentSession(id: "session01", title: "审批", detail: "dsh-links", updatedAt: 2)
        ])

        #expect(store.readRecord(id: "abcd1234")?.text == "按钮颜色反了")
        #expect(store.imageData(for: record) == Data("png!".utf8))
        #expect(store.readRecents().map(\.id) == ["session01"])
        #expect(store.readRecord(id: "../abcd1234") == nil)
        #expect(ShareInbox.imageRecord(bytes: Data("png!".utf8), filename: "../shot.png", id: "abcd1234").isFailure)

        store.consume(id: record.id)
        #expect(store.readRecord(id: record.id) == nil)
        try? FileManager.default.removeItem(at: root)
    }

    @Test("最近会话只保留六个有标题的会话")
    func recentSessionsKeepSixTitledRows() {
        let sessions = (0..<8).map { index in
            SessionSummary(sessionId: "session0\(index)", title: index == 1 ? " " : "任务 \(index)", updatedAt: index)
        }
        let recent = ShareInbox.recentSessions(
            sessions, id: { $0.sessionId }, title: { $0.title }, detail: { _ in "dsh-links" },
            updatedAt: { $0.updatedAt })
        #expect(recent.count == 6)
        #expect(recent.first?.id == "session07")
        #expect(recent.contains { $0.id == "session01" } == false)
    }
}

extension Result {
    var isFailure: Bool {
        if case .failure = self { return true }
        return false
    }
}
