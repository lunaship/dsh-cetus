import DLCore
import DLModels
import Foundation
import Testing

@testable import Cetus

/// C07 §11.2 R11 后半：「取消搜索后能回到原位置」。
///
/// 模型只负责记录/清空锚点（纯逻辑，可测）；实际滚动由 `InboxPage` 的
/// `ScrollViewReader` 消费 `searchRestoreToken`。这里钉住模型的契约：
/// 进入搜索时记下当时的首个可见行，结束搜索时发出一次恢复信号。
@MainActor @Suite(.serialized) struct InboxSearchAnchorTests {
    private func session(_ id: String, updatedAt: Int, awaiting: Bool = false) -> SessionSummary {
        SessionSummary(
            sessionId: id, title: id, updatedAt: updatedAt, running: false, blank: false,
            cwd: "/tmp/ws", origin: "user", awaitingInput: awaiting)
    }

    private func model(sessions: [SessionSummary]) -> InboxModel {
        let model = InboxModel(
            hostID: "host-1",
            service: InboxStubService(sessions: sessions),
            cache: InboxMemoryCache(),
            preferences: InboxPreferences(defaults: UserDefaults(suiteName: UUID().uuidString)!),
            autostart: false)
        model.sessions = sessions
        return model
    }

    @Test("未搜索时不产生恢复信号")
    func noTokenBeforeSearch() {
        let model = self.model(sessions: [session("a", updatedAt: 300)])
        #expect(model.searchReturnAnchor == nil)
        #expect(model.searchRestoreToken == nil)
    }

    @Test("进入搜索时记下首个可见行作为锚点")
    func anchorCapturedOnSearchStart() {
        let model = self.model(sessions: [session("a", updatedAt: 300), session("b", updatedAt: 200)])

        model.query = "a"

        #expect(model.searchReturnAnchor == "a")
        // 进入搜索本身不触发恢复（还在搜索结果里）。
        #expect(model.searchRestoreToken == nil)
    }

    @Test("等待处理的行优先作为锚点（与列表置顶一致）")
    func awaitingRowWinsAsAnchor() {
        let model = self.model(sessions: [
            session("newest", updatedAt: 900),
            session("waiting", updatedAt: 100, awaiting: true),
        ])

        model.query = "a"

        #expect(model.searchReturnAnchor == "waiting")
    }

    @Test("结束搜索发出一次恢复信号，且锚点保留可供滚动")
    func restoreTokenOnSearchEnd() {
        let model = self.model(sessions: [session("a", updatedAt: 300)])

        model.query = "a"
        let before = model.searchRestoreToken
        model.query = ""

        #expect(before == nil)
        #expect(model.searchRestoreToken != nil, "清空搜索必须发出恢复信号")
        #expect(model.searchReturnAnchor == "a", "锚点要留到滚动完成")
    }

    @Test("连续两次搜索各发一次信号（token 变化才能触发 onChange）")
    func secondSearchEmitsNewToken() {
        let model = self.model(sessions: [session("a", updatedAt: 300), session("b", updatedAt: 200)])

        model.query = "a"
        model.query = ""
        let first = model.searchRestoreToken

        model.query = "b"
        model.query = ""
        let second = model.searchRestoreToken

        #expect(first != nil)
        #expect(second != nil)
        #expect(first != second, "token 必须变化，否则第二次取消不会滚动")
    }

    @Test("只输入空白不算进入搜索，不发信号")
    func whitespaceOnlyQueryIsNotASearch() {
        let model = self.model(sessions: [session("a", updatedAt: 300)])

        model.query = "   "

        #expect(model.searchReturnAnchor == nil)
        #expect(model.searchRestoreToken == nil)
    }

    @Test("输入相同值不重复记锚点")
    func sameQueryDoesNotReanchor() {
        let model = self.model(sessions: [session("a", updatedAt: 300), session("b", updatedAt: 200)])

        model.query = "a"
        let anchor = model.searchReturnAnchor
        model.sessions = [session("b", updatedAt: 200), session("a", updatedAt: 300)]
        model.query = "a"

        #expect(model.searchReturnAnchor == anchor)
    }
}

// MARK: - Test doubles

/// Only `load` is overridden; the protocol supplies defaults for the rest.
private struct InboxStubService: InboxServing {
    var sessions: [SessionSummary]

    func load(resetStreams: Bool) async throws -> InboxPayload {
        _ = resetStreams
        return InboxPayload(
            sessions: sessions, archivedIDs: [], workspaces: [], hostName: "host", route: .local,
            eventsEnabled: false, pushVersion: 0, pairedDeviceID: nil)
    }

    func computers() async -> [InboxComputer] { [] }
}
