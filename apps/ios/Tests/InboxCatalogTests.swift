import DLCore
import DLModels
import Foundation
import Testing

@Suite struct InboxCatalogTests {
    private let now = Date(timeIntervalSince1970: 1_780_000_000)

    @Test func sectionsKeepNewestAndStableOrder() {
        let rows = [
            session("old", updatedAt: 100, running: true),
            session("new", updatedAt: 300, awaiting: true),
            session("old", updatedAt: 200, title: "kept", running: true),
            session("done", updatedAt: 50),
        ]
        let groups = inboxSections(rows)
        #expect(groups.map(\.section) == [.awaiting, .running, .recent])
        #expect(groups[0].sessions.map(\.sessionId) == ["new"])
        #expect(groups[1].sessions.map(\.sessionId) == ["old"])
        #expect(groups[1].sessions[0].title == "kept")
        #expect(inboxFiltered(groups, filter: .running).map(\.section) == [.running])
    }

    @Test func visibleDropsArchivedDeletedSubagentAndStaleBlankSeconds() {
        let freshBlank = session("fresh", updatedAt: 1_780_000_000 - 60, blank: true)
        let staleBlank = session("stale", updatedAt: 1_780_000_000 - 26 * 60 * 60, blank: true)
        let oldReal = session("old", updatedAt: 1_000, blank: false)
        let rows = [
            freshBlank,
            staleBlank,
            oldReal,
            session("gone", updatedAt: 10),
            session("archived", updatedAt: 10),
            session("child", updatedAt: 10, origin: "subagent"),
            session("", updatedAt: 10),
        ]
        let visible = inboxVisibleSessions(rows, archivedIDs: ["archived"], deletedIDs: ["gone"], now: now)
        #expect(visible.map(\.sessionId) == ["fresh", "old"])
    }

    @Test func foldersFollowRegistryAndKeepEmptyWorkspaces() {
        let rows = [
            session("owned", updatedAt: 3, cwd: "/tmp/process"),
            session("other", updatedAt: 2, cwd: "/work/app"),
            session("owned", updatedAt: 1, title: "older"),
        ]
        let accounts = [InboxWorkspaceAccount(path: "/work/app/", sessionIDs: ["owned"])]
        let folders = inboxWorkspaceFolders(
            sessions: rows, workspaces: ["/work/lib", "/work/app", "/work/app/"],
            accounts: accounts, registryReady: true)
        #expect(folders.map { $0.path } == ["/work/lib", "/work/app", nil])
        #expect(folders[0].sessions.isEmpty)
        #expect(folders[1].sessions.map { $0.sessionId } == ["owned"])
        #expect(folders[2].sessions.map { $0.sessionId } == ["other"])
    }

    @Test func pinnedAwaitingIsNotRepeatedAndSameNamesKeepParents() {
        let rows = [
            session("need", updatedAt: 5, awaiting: true, cwd: "/a/app"),
            session("one", updatedAt: 4, cwd: "/a/app"),
            session("two", updatedAt: 3, cwd: "/a/app"),
            session("three", updatedAt: 2, cwd: "/a/app"),
            session("four", updatedAt: 1, cwd: "/a/app"),
            session("other", updatedAt: 6, cwd: "/b/app"),
        ]
        let folders = inboxWorkspaceFolders(
            sessions: rows, workspaces: ["/a/app", "/b/app"], accounts: [], registryReady: false)
        let pinned = folders.flatMap { $0.sessions }.filter { $0.awaitingInput == true }
        let folderRows = folders[0].sessions.filter { $0.awaitingInput != true }
        #expect(pinned.map { $0.sessionId } == ["need"])
        #expect(folderRows.prefix(3).map { $0.sessionId } == ["one", "two", "three"])
        #expect(!folderRows.map { $0.sessionId }.contains("need"))
        #expect(inboxWorkspaceLabels(folders.compactMap { $0.path }) == ["/a/app": "a/app", "/b/app": "b/app"])
        #expect(folders[0].awaitingCount == 1)
        #expect(folders[0].runningCount == 0)
    }

    @Test func foldersFallBackToCwdOnlyBeforeRegistry() {
        let rows = [session("loose", updatedAt: 2, cwd: "/work/app/")]
        let ready = inboxWorkspaceFolders(
            sessions: rows, workspaces: [], accounts: [], registryReady: true)
        #expect(ready.map { $0.path } == [nil])
        let early = inboxWorkspaceFolders(
            sessions: rows, workspaces: ["/work/app"], accounts: [], registryReady: false)
        #expect(early[0].path == "/work/app")
        #expect(early[0].sessions.map { $0.sessionId } == ["loose"])
    }

    @Test func workspaceOwnerBeatsCwd() {
        let rows = [
            session("owned", updatedAt: 2, cwd: "/elsewhere/nope"),
            session("cwd", updatedAt: 1, cwd: "/work/app"),
        ]
        let accounts = [InboxWorkspaceAccount(path: "/work/app/", sessionIDs: ["owned"])]
        let matched = inboxSessions(inWorkspace: "/work/app", sessions: rows, accounts: accounts)
        #expect(matched.map(\.sessionId) == ["owned", "cwd"])
        let paths = inboxVisibleWorkspaces([
            WorkspaceInfo(path: "/work/app/"),
            WorkspaceInfo(path: "/tmp/nope"),
            WorkspaceInfo(path: "/work/lib"),
        ])
        #expect(paths == ["/work/app", "/work/lib"])
    }

    @Test func searchSplitsTitleAndContent() {
        let rows = [
            session("title", updatedAt: 3, title: "Fix approval"),
            session("body", updatedAt: 2, title: "Notes", cwd: "/work/approval"),
            session("other", updatedAt: 1, title: "Unrelated"),
        ]
        let candidates = inboxSearchCandidates(rows, needle: "approval", serverIDs: ["body"])
        let groups = inboxSearchGroups(
            sessions: candidates, needle: "approval", snippets: ["body": "hit approval"])
        #expect(groups.titleMatches.map(\.sessionId) == ["title"])
        #expect(groups.contentMatches.map(\.session.sessionId) == ["body"])
        #expect(groups.contentMatches[0].snippet == "hit approval")
        let ranges = inboxMatchRanges(in: "Fix Approval", needle: "approval")
        #expect(ranges.count == 1)
    }

    @Test func rowTextsFollowAndroid() {
        let running = session(
            "run", updatedAt: 1, running: true, cwd: "/src/app", subagents: 2,
            activity: SessionActivity(kind: .tool, label: "npm test", step: 3))
        let runningRow = inboxRowContent(session: running, action: nil, offline: false)
        #expect(runningRow.dot == .accent)
        #expect(runningRow.status == nil)
        #expect(runningRow.preview == .tool(label: "npm test", step: 3))
        #expect(runningRow.subagentCount == 2)
        #expect(runningRow.allowsSwipe)

        let offline = inboxRowContent(session: running, action: nil, offline: true)
        #expect(offline.dot == nil)
        #expect(offline.preview == .lastSeenTool(label: "npm test", step: 3))

        let approval = InboxPhoneAction.approval(sessionID: "wait", approvalID: "a1", toolName: "bash")
        let waiting = session("wait", updatedAt: 1, running: true, awaiting: true, cwd: "/src/app")
        let approvalRow = inboxRowContent(session: waiting, action: approval, offline: false)
        #expect(approvalRow.dot == .wait)
        #expect(approvalRow.status == .waitingApproval)
        #expect(approvalRow.command == "bash")
        #expect(!approvalRow.allowsSwipe)

        let question = InboxPhoneAction.question(sessionID: "wait", rpcID: "q1", prompt: "Which file?")
        let questionRow = inboxRowContent(session: waiting, action: question, offline: false)
        #expect(questionRow.status == .waitingAnswer)
        #expect(questionRow.preview == .question("Which file?"))
        #expect(questionRow.allowsSwipe)

        let bare = inboxRowContent(session: waiting, action: nil, offline: false)
        #expect(bare.status == .waiting)
        #expect(bare.pending == .none)
        #expect(bare.command == nil)
        #expect(bare.allowsSwipe)
        let approved = session(
            "approve", updatedAt: 1, running: true, awaiting: true, hostWait: .awaitingApproval)
        #expect(inboxRowContent(session: approved, action: nil, offline: false).status == .waitingApproval)
        let asked = session(
            "ask", updatedAt: 1, running: true, awaiting: true, hostWait: .awaitingInput)
        let askedRow = inboxRowContent(session: asked, action: nil, offline: false)
        #expect(askedRow.status == .waitingAnswer)
        #expect(askedRow.pending == .none)
        #expect(askedRow.preview == nil)

        let done = session("done", updatedAt: 1, lastResult: SessionLastResult(files: 2))
        #expect(inboxRowContent(session: done, action: nil, offline: false).status == .done)
        #expect(inboxRowContent(session: done, action: nil, offline: false).preview == .files(2))
        let stopped = session("stop", updatedAt: 1, stopped: "interrupted")
        #expect(inboxRowContent(session: stopped, action: nil, offline: false).status == .stopped(.interrupted))
    }

    @Test func hostWaitStaysOutOfSessionJSON() throws {
        let row = session("s", updatedAt: 1, awaiting: true, hostWait: .awaitingInput)
        let data = try JSONEncoder().encode(row)
        let object = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        #expect(object["hostWait"] == nil)
        #expect(object["awaitingInput"] as? Bool == true)
        let decoded = try JSONDecoder().decode(SessionSummary.self, from: data)
        #expect(decoded.hostWait == nil)
        #expect(decoded.awaitingInput == true)
    }

    @Test func hostEventMovesSessionBetweenStates() {
        var rows = [session("s", updatedAt: 1, running: true)]
        rows = inboxApplying(event("s", .awaitingApproval, title: "Wait"), to: rows, nowMillis: 2_000)
        #expect(rows[0].awaitingInput == true)
        #expect(rows[0].hostWait == .awaitingApproval)
        #expect(inboxRowContent(session: rows[0], action: nil, offline: false).status == .waitingApproval)
        rows = inboxApplying(event("s", .awaitingInput), to: rows, nowMillis: 2_500)
        #expect(rows[0].hostWait == .awaitingInput)
        #expect(inboxRowContent(session: rows[0], action: nil, offline: false).status == .waitingAnswer)
        #expect(rows[0].running == true)
        #expect(rows[0].title == "Wait")
        rows = inboxApplying(event("s", .completed), to: rows, nowMillis: 3_000)
        #expect(rows[0].running == false)
        #expect(rows[0].hostWait == nil)
        #expect(rows[0].stoppedReason == nil)
        rows = inboxApplying(event("s", .failed), to: rows, nowMillis: 4_000)
        #expect(rows[0].stoppedReason == "error")
        #expect(rows[0].hostWait == nil)
        rows = inboxApplying(event("missing", .running), to: rows, nowMillis: 5_000)
        #expect(rows.contains { $0.sessionId == "missing" && $0.running == true })
    }

    @Test func timeBucketsUseCalendarDays() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let noon = Date(timeIntervalSince1970: 1_780_000_000)
        #expect(inboxTime(millis(noon.addingTimeInterval(-10)), now: noon, calendar: calendar) == .justNow)
        #expect(inboxTime(millis(noon.addingTimeInterval(-120)), now: noon, calendar: calendar) == .minutes(2))
        #expect(inboxTime(millis(noon.addingTimeInterval(-3 * 3_600)), now: noon, calendar: calendar) == .hours(3))
        let yesterday = inboxTime(millis(noon.addingTimeInterval(-26 * 3_600)), now: noon, calendar: calendar)
        #expect(yesterday == .yesterday)
        let weekday = inboxTime(millis(noon.addingTimeInterval(-3 * 86_400)), now: noon, calendar: calendar)
        if case .weekday = weekday {} else { Issue.record("expected a weekday") }
        #expect(inboxTime(millis(noon.addingTimeInterval(-10 * 86_400)), now: noon, calendar: calendar) == .days(10))
    }

    @Test func displayTitleUsesLeaf() {
        #expect(inboxDisplayTitle("@/work/app fix the bug") == "app fix the bug")
        #expect(inboxDisplayTitle("plain title") == "plain title")
    }

    @Test func phoneActionUsesNewestPendingAndPrefersApprovalOnTie() {
        let rows = [
            session("older", updatedAt: 1, awaiting: true),
            session("newer", updatedAt: 5, awaiting: true),
        ]
        #expect(inboxActionableSessionID(rows) == "newer")
        let response = RequestsSnapshotResponse(
            approvals: [
                PendingApproval(approvalId: "old", status: .resolved, createdAt: 9, toolName: "nope"),
                PendingApproval(approvalId: "yes", status: .pending, createdAt: 4, toolName: "bash"),
            ],
            questions: [
                PendingQuestion(
                    rpcId: "q", status: .pending, createdAt: 4,
                    questions: [
                        ClarifyingQuestion(question: "Pick one")
                    ])
            ])
        let action = inboxPhoneAction(sessionID: "newer", response: response)
        #expect(action == .approval(sessionID: "newer", approvalID: "yes", toolName: "bash"))
    }

    private func session(
        _ id: String,
        updatedAt: Int,
        title: String? = nil,
        running: Bool = false,
        awaiting: Bool = false,
        blank: Bool = false,
        cwd: String? = nil,
        origin: String? = nil,
        subagents: Int? = nil,
        activity: SessionActivity? = nil,
        lastResult: SessionLastResult? = nil,
        stopped: String? = nil,
        hostWait: HostSessionState? = nil
    ) -> SessionSummary {
        SessionSummary(
            sessionId: id, title: title, updatedAt: updatedAt, running: running, blank: blank, cwd: cwd,
            origin: origin, subagentCount: subagents, awaitingInput: awaiting, activity: activity,
            lastResult: lastResult, stoppedReason: stopped, hostWait: hostWait)
    }

    private func event(_ id: String, _ state: HostSessionState, title: String? = nil) -> HostSessionStateEvent {
        HostSessionStateEvent(type: "session/state", sessionId: id, state: state, title: title, seq: 1)
    }

    private func millis(_ date: Date) -> Int {
        Int(date.timeIntervalSince1970 * 1000)
    }
}
