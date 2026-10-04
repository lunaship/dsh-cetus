import DLCore
import DLModels
import Foundation
import Testing

@testable import DeepLinks

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

    @Test func archiveFailureStaysAndSuccessHidesUntilTheServerDropsIt() async {
        let script = Script(load: .success(payload([session("s", updatedAt: 1)])))
        await script.setArchiveError(.failed)
        let model = make("h", script: script, cache: InboxMemoryCache())
        await model.refresh()
        await model.archive(session("s", updatedAt: 1))
        #expect(model.visibleSessions.map(\.sessionId) == ["s"])
        #expect(model.notice == .archive)
        await script.setArchiveError(nil)
        await model.archive(session("s", updatedAt: 1))
        #expect(model.visibleSessions.isEmpty)
        #expect(await script.archiveCount() == 1)
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
        await model.refresh()
        model.askDelete(session("s", updatedAt: 1, title: "Notes"))
        #expect(model.visibleSessions.map(\.sessionId) == ["s"])
        #expect(await script.archiveCount() == 0)
        await model.commitDelete()
        #expect(model.visibleSessions.isEmpty)
        #expect(model.archivedSessions.isEmpty)
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

    @Test func previewServiceInheritsProtocolDefaults() async {
        let service = EmptyInboxService()
        let events = await service.openEvents()
        var saw = false
        for await _ in events { saw = true }
        #expect(!saw)
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
        _ sessions: [SessionSummary], route: InboxRouteKind = .local, archived: [String] = []
    ) -> InboxPayload {
        InboxPayload(
            sessions: sessions, archivedIDs: archived, workspaces: [], hostName: "Mac", route: route,
            eventsEnabled: false)
    }

    private func cached(_ sessions: [SessionSummary], name: String) -> InboxCacheSnapshot {
        InboxCacheSnapshot(
            sessions: sessions, archivedIDs: [], workspaces: [], hostName: name, route: .local, eventsEnabled: false)
    }

    private func session(
        _ id: String, updatedAt: Int, title: String? = nil, running: Bool = false, awaiting: Bool = false
    ) -> SessionSummary {
        SessionSummary(
            sessionId: id, title: title, updatedAt: updatedAt, running: running, awaitingInput: awaiting)
    }
}
