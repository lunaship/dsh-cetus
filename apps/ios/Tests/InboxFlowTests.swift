import DLCore
import DLModels
import Foundation
import Testing

@testable import Cetus

@MainActor @Suite(.serialized) struct InboxFlowTests {
    private actor EmptyInboxService: InboxServing {}

    private actor Script: InboxServing {
        var loadResult: Result<InboxPayload, InboxServiceError>
        var requests = RequestsSnapshotResponse()
        var archiveError: InboxServiceError?
        var archives: [String] = []
        var decisions: [(String, String, String)] = []
        var credentialDeletes = 0

        init(load: Result<InboxPayload, InboxServiceError>) { loadResult = load }

        func load(resetStreams: Bool) async throws -> InboxPayload {
            _ = resetStreams
            return try loadResult.get()
        }

        func requests(sessionID: String) async throws -> RequestsSnapshotResponse {
            _ = sessionID
            return requests
        }

        func archive(sessionID: String) async throws {
            if let archiveError { throw archiveError }
            archives.append(sessionID)
        }

        func decide(sessionID: String, approvalID: String, outcome: String) async throws {
            decisions.append((sessionID, approvalID, outcome))
        }

        func setLoad(_ result: Result<InboxPayload, InboxServiceError>) { loadResult = result }
        func setArchiveError(_ error: InboxServiceError?) { archiveError = error }
        func setRequests(_ value: RequestsSnapshotResponse) { requests = value }
        func archiveCount() -> Int { archives.count }
        func decisionLog() -> [(String, String, String)] { decisions }
        func deletes() -> Int { credentialDeletes }
    }

    @Test func refreshFailureKeepsCacheAndSuccessReplacesIt() async {
        let cache = InboxMemoryCache()
        cache.save(hostID: "h", snapshot: cached([session("cached", updatedAt: 1)], name: "Mac"))
        let script = Script(load: .failure(.offline))
        let model = make("h", script: script, cache: cache)
        #expect(model.sessions.map(\.sessionId) == ["cached"])
        #expect(model.link == .checking(.local))
        await model.refresh()
        #expect(model.link == .offline)
        #expect(model.sessions.map(\.sessionId) == ["cached"])
        #expect(!model.missingHost)
        await script.setLoad(.success(payload([session("live", updatedAt: 2)], route: .remote)))
        await model.refresh()
        #expect(model.link == .online(.remote))
        #expect(model.sessions.map(\.sessionId) == ["live"])
        #expect(await script.deletes() == 0)
    }

    @Test func unauthorizedKeepsSessionsAndDoesNotDropTheHost() async {
        let cache = InboxMemoryCache()
        cache.save(hostID: "h", snapshot: cached([session("cached", updatedAt: 1)], name: "Mac"))
        let script = Script(load: .failure(.unauthorized))
        let model = make("h", script: script, cache: cache)
        await model.refresh()
        #expect(model.notice == .unauthorized)
        #expect(model.link == .offline)
        #expect(!model.missingHost)
        #expect(model.sessions.map(\.sessionId) == ["cached"])
        #expect(await script.deletes() == 0)
    }

    @Test func certificateChangeStopsTheLinkAndKeepsCredentials() async {
        let cache = InboxMemoryCache()
        cache.save(hostID: "h", snapshot: cached([session("cached", updatedAt: 1)], name: "Mac"))
        let script = Script(load: .failure(.certificate))
        let model = make("h", script: script, cache: cache)
        await model.refresh()
        #expect(model.notice == .certificate)
        #expect(model.link == .offline)
        #expect(!model.missingHost)
        #expect(model.sessions.map(\.sessionId) == ["cached"])
        #expect(await script.deletes() == 0)
    }

    @Test func archiveFailureStaysAndSuccessHidesUntilTheServerDropsIt() async {
        let script = Script(load: .success(payload([session("s", updatedAt: 1)])))
        await script.setArchiveError(.failed)
        let model = make("h", script: script, cache: InboxMemoryCache())
        var discarded = 0
        model.discardDrafts = { _, _ in discarded += 1 }
        await model.refresh()
        await model.archive(session("s", updatedAt: 1))
        #expect(model.visibleSessions.map(\.sessionId) == ["s"])
        #expect(model.notice == .archive)
        await script.setArchiveError(nil)
        await model.archive(session("s", updatedAt: 1))
        #expect(model.visibleSessions.isEmpty)
        #expect(await script.archiveCount() == 1)
        // 归档可恢复，草稿必须保留。
        #expect(discarded == 0)
        await script.setLoad(.success(payload([session("s", updatedAt: 1)], archived: ["s"])))
        await model.refresh()
        #expect(model.archivedSessions.map(\.sessionId) == ["s"])
        #expect(model.visibleSessions.isEmpty)
        await script.setLoad(.success(payload([session("s", updatedAt: 1)])))
        await model.refresh()
        #expect(model.visibleSessions.map(\.sessionId) == ["s"])
    }

    @Test func deleteWaitsForConfirmationAndStaysDeleted() async {
        let script = Script(load: .success(payload([session("s", updatedAt: 1, title: "Notes")])))
        let model = make("h", script: script, cache: InboxMemoryCache())
        var discarded: [String] = []
        model.discardDrafts = { hostID, sessionID in discarded.append("\(hostID)/\(sessionID)") }
        await model.refresh()
        model.askDelete(session("s", updatedAt: 1, title: "Notes"))
        #expect(model.visibleSessions.map(\.sessionId) == ["s"])
        #expect(await script.archiveCount() == 0)
        await model.commitDelete()
        #expect(model.visibleSessions.isEmpty)
        #expect(model.archivedSessions.isEmpty)
        // C02 要求 9：删除成功后只丢弃这一个会话的草稿。
        #expect(discarded == ["h/s"])
        await script.setLoad(.success(payload([session("s", updatedAt: 1, title: "Notes")])))
        await model.refresh()
        #expect(model.visibleSessions.isEmpty)
        #expect(model.archivedSessions.isEmpty)
    }

    @Test func approveSendsAllowedOnceAndDoesNothingOffline() async {
        let waiting = session("s", updatedAt: 5, running: true, awaiting: true)
        let script = Script(load: .success(payload([waiting])))
        await script.setRequests(
            RequestsSnapshotResponse(approvals: [
                PendingApproval(approvalId: "a1", status: .pending, createdAt: 1, toolName: "bash")
            ]))
        let model = make("h", script: script, cache: InboxMemoryCache())
        await model.refresh()
        #expect(model.phoneAction == .approval(sessionID: "s", approvalID: "a1", toolName: "bash"))
        await script.setLoad(.failure(.offline))
        await model.refresh()
        let before = await script.decisionLog().count
        await model.decide(allow: true)
        #expect(await script.decisionLog().count == before)
        await script.setLoad(.success(payload([waiting])))
        await model.refresh()
        await model.decide(allow: true)
        #expect(await script.decisionLog().map { "\($0.0):\($0.1):\($0.2)" } == ["s:a1:allowed-once"])
        #expect(model.approvalTick == 1)
    }

    @Test func bareWaitingRowUsesHostKindUntilReload() async {
        let approval = session("approve", updatedAt: 2, running: true, awaiting: true)
        let question = session("ask", updatedAt: 1, running: true, awaiting: true)
        let script = Script(load: .success(payload([approval, question])))
        let model = make("h", script: script, cache: InboxMemoryCache())
        await model.refresh()
        let loaded = model.sessions.map { row in
            inboxRowContent(session: row, action: model.phoneAction, offline: false).status
        }
        #expect(loaded == [.waiting, .waiting])

        await model.apply(
            .state(
                HostSessionStateEvent(
                    type: "session/state", sessionId: "ask", state: .awaitingInput, seq: 7)))
        let asked = model.sessions.first { $0.sessionId == "ask" }
        #expect(asked?.hostWait == .awaitingInput)
        #expect(inboxRowContent(session: asked!, action: model.phoneAction, offline: false).status == .waitingAnswer)
        #expect(model.sessions.first { $0.sessionId == "approve" }?.hostWait == nil)

        await model.refresh()
        let reloaded = model.sessions.first { $0.sessionId == "ask" }
        #expect(reloaded?.hostWait == nil)
        #expect(inboxRowContent(session: reloaded!, action: nil, offline: false).status == .waiting)
    }

    @Test func previewServiceInheritsProtocolDefaults() async {
        let service = EmptyInboxService()
        let events = await service.openEvents()
        var saw = false
        for await _ in events { saw = true }
        #expect(!saw)
    }

    @Test func folderExpansionIsRememberedPerComputer() {
        let suite = "inbox-tests-(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let preferences = InboxPreferences(defaults: defaults)
        let first = InboxModel(
            hostID: "mac", service: Script(load: .success(payload([]))), cache: InboxMemoryCache(),
            preferences: preferences, autostart: false)
        first.toggleFolder("workspace:/work/app")
        first.togglePreview("workspace:/work/app")
        let second = InboxModel(
            hostID: "mac", service: Script(load: .success(payload([]))), cache: InboxMemoryCache(),
            preferences: preferences, autostart: false)
        let other = InboxModel(
            hostID: "other", service: Script(load: .success(payload([]))), cache: InboxMemoryCache(),
            preferences: preferences, autostart: false)
        #expect(second.collapsedFolders == ["workspace:/work/app"])
        #expect(second.expandedPreviews == ["workspace:/work/app"])
        #expect(other.collapsedFolders.isEmpty)
        #expect(other.expandedPreviews.isEmpty)
    }
    @Test func productionHomeCoversInlineSearchAndDelete() async {
        let rows = [
            session("need", updatedAt: 6, awaiting: true, cwd: "/work/app"),
            session("one", updatedAt: 5, cwd: "/work/app"),
            session("two", updatedAt: 4, cwd: "/work/app"),
            session("three", updatedAt: 3, cwd: "/work/app"),
            session("four", updatedAt: 2, cwd: "/work/app"),
        ]
        let script = Script(load: .success(payload(rows, workspaces: ["/work/app"])))
        await script.setRequests(
            RequestsSnapshotResponse(approvals: [PendingApproval(approvalId: "a", status: .pending, toolName: "bash")])
        )
        let model = make("h", script: script, cache: InboxMemoryCache())
        await model.refresh()
        let page = InboxPage(model: model)
        let copy = InboxCopy(locale: Locale(identifier: "zh-Hans"))
        var surface = page.homeSurface(copy: copy)
        #expect(surface.more == ["设置", "已归档"])
        #expect(!surface.more.contains("添加工作区"))
        let pinned = surface.sections[0].entries[0]
        #expect(pinned.workspace == "app")
        #expect(pinned.actions.map { $0.title } == ["拒绝", "允许一次", "删除"])
        #expect(pinned.actions[2].confirmsDelete)
        let folder = surface.sections[1]
        #expect(folder.entries.map { $0.title } == ["one", "two", "three"])
        #expect(folder.entries.allSatisfy { $0.compact && $0.workspace == nil })
        #expect(folder.toggle == "显示全部 4 个")
        model.togglePreview(folder.id)
        surface = page.homeSurface(copy: copy)
        #expect(surface.sections[1].entries.count == 4)
        #expect(surface.sections[1].toggle == "收起")
        model.query = "one"
        model.useSearchResult(
            InboxSearchPayload(items: [SessionSearchItem(sessionId: "one", snippet: "found")], degraded: false))
        surface = page.homeSurface(copy: copy)
        #expect(surface.search.map { $0.title } == ["one"])
        model.askDelete(rows[1])
        surface = page.homeSurface(copy: copy)
        #expect(surface.deleteSession == "one")
        model.deleteWorkspacePath = "/work/app"
        surface = page.homeSurface(copy: copy)
        #expect(surface.deleteWorkspace == "/work/app")
        model.link = .offline
        model.query = ""
        model.useSearchResult(nil)
        surface = page.homeSurface(copy: copy)
        #expect(surface.sections[0].entries[0].actions.prefix(2).allSatisfy { !$0.enabled })
        #expect(surface.sections[1].createEnabled == false)
    }

    @Test func searchIgnoresTheStatusFilter() async {
        let rows = [
            session("run", updatedAt: 2, title: "approval run", running: true),
            session("done", updatedAt: 1, title: "approval done"),
        ]
        let script = Script(load: .success(payload(rows)))
        let model = make("h", script: script, cache: InboxMemoryCache())
        await model.refresh()
        model.filter = .running
        model.query = "approval"
        model.useSearchResult(InboxSearchPayload(items: [], degraded: false))
        guard case .search(let groups, _, _) = model.presentation else {
            Issue.record("expected search")
            return
        }
        #expect(groups.titleMatches.map(\.sessionId) == ["run", "done"])
    }

    private func make(_ hostID: String, script: Script, cache: InboxMemoryCache) -> InboxModel {
        let suite = "inbox-tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return InboxModel(
            hostID: hostID, service: script, cache: cache, preferences: InboxPreferences(defaults: defaults),
            autostart: false, now: Date(timeIntervalSince1970: 1_780_000_000))
    }

    private func payload(
        _ sessions: [SessionSummary], route: InboxRouteKind = .local, archived: [String] = [],
        workspaces: [String] = []
    ) -> InboxPayload {
        InboxPayload(
            sessions: sessions, archivedIDs: archived,
            workspaces: workspaces.map {
                WorkspaceInfo(path: $0, sessionIds: sessions.compactMap { $0.sessionId })
            }, hostName: "Mac", route: route,
            eventsEnabled: false)
    }

    private func cached(_ sessions: [SessionSummary], name: String) -> InboxCacheSnapshot {
        InboxCacheSnapshot(
            sessions: sessions, archivedIDs: [], workspaces: [], hostName: name, route: .local, eventsEnabled: false)
    }

    private func session(
        _ id: String, updatedAt: Int, title: String? = nil, running: Bool = false, awaiting: Bool = false,
        cwd: String? = nil
    ) -> SessionSummary {
        SessionSummary(
            sessionId: id, title: title ?? id, updatedAt: updatedAt, running: running, cwd: cwd,
            awaitingInput: awaiting)
    }
}

// MARK: - C14：删除/归档后返回栈与当前目标必须更新

@MainActor @Suite(.serialized) struct InboxDeleteNavigationTests {
    private actor ArchiveScript: InboxServing {
        var archives: [String] = []
        func load(resetStreams: Bool) async throws -> InboxPayload {
            _ = resetStreams
            return InboxPayload(
                sessions: [
                    SessionSummary(sessionId: "s1", title: "One", cwd: "/work"),
                    SessionSummary(sessionId: "s2", title: "Two", cwd: "/work"),
                ],
                archivedIDs: [], workspaces: [], hostName: "mac", route: .local, eventsEnabled: false)
        }
        func archive(sessionID: String) async throws { archives.append(sessionID) }
    }

    private func makeModel() -> (InboxModel, ArchiveScript) {
        let script = ArchiveScript()
        let model = InboxModel(
            hostID: "mac", service: script, cache: InboxMemoryCache(), autostart: false)
        return (model, script)
    }

    /// 归档正在浏览的会话后，导航栈里不能再留着它 —— 否则用户停在死页面上
    /// 继续发消息/点审批，而服务端已不再把它当活跃会话（C14）。
    @Test func archivingOpenSessionPopsIt() async {
        let (model, _) = makeModel()
        await model.refresh()
        model.path = [.session("s1")]
        await model.archive(SessionSummary(sessionId: "s1", title: "One", cwd: "/work"))
        #expect(!model.path.contains(.session("s1")), "归档后不该还停在已归档的会话页")
    }

    /// 只摘掉被归档的那个会话，用户在其之上打开的其他页面保持不动。
    @Test func archivingKeepsOtherDestinations() async {
        let (model, _) = makeModel()
        await model.refresh()
        model.path = [.session("s1"), .settings]
        await model.archive(SessionSummary(sessionId: "s1", title: "One", cwd: "/work"))
        #expect(model.path == [.settings], "不应连带清掉设置页")
    }

    /// 归档**别的**会话时，当前停留在的会话页不能被误关。
    @Test func archivingDifferentSessionKeepsCurrent() async {
        let (model, _) = makeModel()
        await model.refresh()
        model.path = [.session("s2")]
        model.selectedSessionID = "s2"
        await model.archive(SessionSummary(sessionId: "s1", title: "One", cwd: "/work"))
        #expect(model.path == [.session("s2")], "归档 s1 不该影响正在看的 s2")
        #expect(model.selectedSessionID == "s2")
    }

    /// 归档失败时**不动**导航 —— 服务端没接受，用户应该留在原地看错误。
    @Test func failedArchiveKeepsNavigation() async {
        let script = FailingArchiveScript()
        let model = InboxModel(hostID: "mac", service: script, cache: InboxMemoryCache(), autostart: false)
        await model.refresh()
        model.path = [.session("s1")]
        await model.archive(SessionSummary(sessionId: "s1", title: "One", cwd: "/work"))
        #expect(model.path == [.session("s1")], "归档失败不该把用户踹出页面")
        #expect(model.notice == .archive)
    }

    private actor FailingArchiveScript: InboxServing {
        func load(resetStreams: Bool) async throws -> InboxPayload {
            _ = resetStreams
            return InboxPayload(
                sessions: [SessionSummary(sessionId: "s1", title: "One", cwd: "/work")],
                archivedIDs: [], workspaces: [], hostName: "mac", route: .local, eventsEnabled: false)
        }
        func archive(sessionID: String) async throws { throw InboxServiceError.failed }
    }
}

// MARK: - C14：错误不是空状态，且不同原因分别表达

@MainActor @Suite(.serialized) struct InboxOfflineReasonTests {
    private actor RejectingService: InboxServing {
        func load(resetStreams: Bool) async throws -> InboxPayload {
            _ = resetStreams
            throw InboxServiceError.unauthorized
        }
    }

    private actor UnreachableService: InboxServing {
        func load(resetStreams: Bool) async throws -> InboxPayload {
            _ = resetStreams
            throw InboxServiceError.offline
        }
    }

    private actor EmptyService: InboxServing {
        func load(resetStreams: Bool) async throws -> InboxPayload {
            _ = resetStreams
            return InboxPayload(
                sessions: [], archivedIDs: [], workspaces: [], hostName: "mac", route: .local,
                eventsEnabled: false)
        }
    }

    /// 未授权是「配对失效」，重试没有用 —— 必须与「连不上」区分开（C14）。
    @Test func unauthorizedIsRejectedNotUnreachable() async {
        let model = InboxModel(
            hostID: "mac", service: RejectingService(), cache: InboxMemoryCache(), autostart: false)
        await model.refresh()
        #expect(model.presentation == .offlineEmpty(reason: .rejected))
    }

    /// 连不上是「可重试」，与配对失效不同。
    @Test func offlineIsUnreachable() async {
        let model = InboxModel(
            hostID: "mac", service: UnreachableService(), cache: InboxMemoryCache(), autostart: false)
        await model.refresh()
        #expect(model.presentation == .offlineEmpty(reason: .unreachable))
    }

    /// 真正没有任务的账号走 starters，不能被报成错误（C14：错误不是空状态，
    /// 反过来空状态也不能被报成错误）。
    @Test func genuinelyEmptyAccountStillShowsStarters() async {
        let model = InboxModel(
            hostID: "mac", service: EmptyService(), cache: InboxMemoryCache(), autostart: false)
        await model.refresh()
        #expect(model.notice == nil)
        #expect(model.presentation == .starters)
    }
}
