import DLCore
import DLModels
import DLNet
import Foundation
import Observation

enum InboxRouteKind: String, Codable, Equatable, Sendable {
    case local
    case remote
}

enum InboxLink: Equatable, Sendable {
    case checking(InboxRouteKind?)
    case online(InboxRouteKind)
    case offline

    var isOnline: Bool {
        if case .online = self { return true }
        return false
    }
}

enum InboxNotice: Equatable, Sendable {
    case unauthorized
    case certificate
    case load
    case archive
    case fork
    case rename
    case approval
    case search
    case delete
    case pushMissing
}

enum InboxDestination: Hashable, Sendable {
    case session(String)
    case settings
    case computer(String)
    case diagnostics
    case newTask(String)
    case addWorkspace
    case archived
}

enum InboxPresentation: Equatable, Sendable {
    case loading
    case starters
    case workspaceEmpty
    case offlineEmpty
    case search(InboxSearchGroups, degraded: Bool, failed: Bool)
    case folders([InboxWorkspaceFolder])
}

struct InboxComputer: Equatable, Identifiable, Sendable {
    var id: String
    var name: String
}

struct InboxComputerRow: Equatable, Identifiable, Sendable {
    var id: String
    var title: String
    var current: Bool
}

struct InboxWorkspaceToken: Identifiable, Hashable, Sendable {
    var path: String
    var name: String
    var id: String { path }
}

struct InboxPayload: Equatable, Sendable {
    var sessions: [SessionSummary]
    var archivedIDs: [String]
    var workspaces: [WorkspaceInfo]
    var hostName: String
    var route: InboxRouteKind
    var eventsEnabled: Bool
    var pushVersion: Int = 0
    var pairedDeviceID: String?
}

struct InboxSearchPayload: Equatable, Sendable {
    var items: [SessionSearchItem]
    var degraded: Bool
}

enum InboxLiveSignal: Equatable, Sendable {
    case state(HostSessionStateEvent)
    case resync
    case unauthorized
}

struct InboxCacheSnapshot: Codable, Equatable, Sendable {
    var sessions: [SessionSummary]
    var archivedIDs: [String]
    var workspaces: [WorkspaceInfo]
    var hostName: String
    var route: InboxRouteKind?
    /// Session list only. Never a token or certificate.
    var eventsEnabled: Bool
}

protocol InboxCaching: Sendable {
    func load(hostID: String) -> InboxCacheSnapshot?
    func save(hostID: String, snapshot: InboxCacheSnapshot)
}

final class InboxMemoryCache: InboxCaching, @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: InboxCacheSnapshot] = [:]

    func load(hostID: String) -> InboxCacheSnapshot? {
        lock.withLock { values[hostID] }
    }

    func save(hostID: String, snapshot: InboxCacheSnapshot) {
        lock.withLock { values[hostID] = snapshot }
    }
}

struct InboxDiskCache: InboxCaching {
    var directory: URL

    init(directory: URL? = nil) {
        if let directory {
            self.directory = directory
        } else {
            let base =
                FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
                ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
            self.directory = base.appendingPathComponent("inbox", isDirectory: true)
        }
    }

    func load(hostID: String) -> InboxCacheSnapshot? {
        guard let data = try? Data(contentsOf: file(hostID: hostID)) else { return nil }
        return try? JSONDecoder().decode(InboxCacheSnapshot.self, from: data)
    }

    func save(hostID: String, snapshot: InboxCacheSnapshot) {
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try? data.write(to: file(hostID: hostID), options: .atomic)
    }

    private func file(hostID: String) -> URL {
        let name = hostID.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "host"
        return directory.appendingPathComponent("\(name).json")
    }
}

struct InboxPreferences {
    var defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func workspace(hostID: String) -> String? {
        let value = defaults.string(forKey: key("workspace", hostID))
        guard let value, !value.isEmpty else { return nil }
        return value
    }

    func setWorkspace(_ path: String?, hostID: String) {
        defaults.set(path, forKey: key("workspace", hostID))
    }

    func deletedIDs(hostID: String) -> Set<String> {
        Set(defaults.stringArray(forKey: key("deleted", hostID)) ?? [])
    }

    func setDeletedIDs(_ ids: Set<String>, hostID: String) {
        defaults.set(ids.sorted(), forKey: key("deleted", hostID))
    }

    func recentSearches(hostID: String) -> [String] {
        defaults.stringArray(forKey: key("recent", hostID)) ?? []
    }

    func setRecentSearches(_ values: [String], hostID: String) {
        defaults.set(values, forKey: key("recent", hostID))
    }

    func collapsedFolders(hostID: String) -> Set<String> {
        Set(defaults.stringArray(forKey: key("collapsed", hostID)) ?? [])
    }

    func setCollapsedFolders(_ keys: Set<String>, hostID: String) {
        defaults.set(keys.sorted(), forKey: key("collapsed", hostID))
    }

    func expandedPreviews(hostID: String) -> Set<String> {
        Set(defaults.stringArray(forKey: key("preview", hostID)) ?? [])
    }

    func setExpandedPreviews(_ keys: Set<String>, hostID: String) {
        defaults.set(keys.sorted(), forKey: key("preview", hostID))
    }

    func lastOnline(hostID: String) -> Date? {
        let stamp = defaults.double(forKey: key("online", hostID))
        guard stamp > 0 else { return nil }
        return Date(timeIntervalSince1970: stamp)
    }

    func setLastOnline(_ date: Date, hostID: String) {
        defaults.set(date.timeIntervalSince1970, forKey: key("online", hostID))
    }

    private func key(_ name: String, _ hostID: String) -> String {
        "inbox.\(name).\(hostID)"
    }
}

protocol InboxServing: Sendable {
    func load(resetStreams: Bool) async throws -> InboxPayload
    func search(query: String) async throws -> InboxSearchPayload
    func requests(sessionID: String) async throws -> RequestsSnapshotResponse
    func rename(sessionID: String, title: String) async throws
    func fork(sessionID: String) async throws -> String
    func archive(sessionID: String) async throws
    func decide(sessionID: String, approvalID: String, outcome: String) async throws
    func computers() async -> [InboxComputer]
    func networkChanges() async -> AsyncStream<Void>
    func openEvents() async -> AsyncStream<InboxLiveSignal>
    func commitHostEvent(_ seq: Int) async
    func resumeHostEvents() async
    func setPhase(_ phase: AppPhase) async
    func stop() async
    func agentPresets() async throws -> [AgentPreset]
    func createSession(preset: String?, workspaceID: String?, cwd: String?) async throws -> String
    func sendPrompt(sessionID: String, text: String, images: [PromptImage]) async throws
    func createWorkspace(path: String) async throws -> WorkspaceWriteResult
    func deleteWorkspace(path: String) async throws
}

enum WorkspaceWriteResult: Equatable, Sendable {
    case created(WorkspaceInfo)
    case pending(String)
}

extension InboxServing {
    func load(resetStreams: Bool) async throws -> InboxPayload {
        _ = resetStreams
        throw InboxServiceError.offline
    }

    func search(query: String) async throws -> InboxSearchPayload {
        _ = query
        return InboxSearchPayload(items: [], degraded: false)
    }

    func requests(sessionID: String) async throws -> RequestsSnapshotResponse {
        _ = sessionID
        return RequestsSnapshotResponse()
    }

    func rename(sessionID: String, title: String) async throws {
        _ = (sessionID, title)
        throw InboxServiceError.failed
    }

    func fork(sessionID: String) async throws -> String {
        _ = sessionID
        throw InboxServiceError.failed
    }

    func archive(sessionID: String) async throws {
        _ = sessionID
        throw InboxServiceError.failed
    }

    func decide(sessionID: String, approvalID: String, outcome: String) async throws {
        _ = (sessionID, approvalID, outcome)
        throw InboxServiceError.failed
    }

    func computers() async -> [InboxComputer] { [] }

    func networkChanges() async -> AsyncStream<Void> {
        AsyncStream { continuation in continuation.finish() }
    }

    func openEvents() async -> AsyncStream<InboxLiveSignal> {
        AsyncStream { continuation in continuation.finish() }
    }

    func commitHostEvent(_ seq: Int) async { _ = seq }

    func resumeHostEvents() async {}

    func setPhase(_ phase: AppPhase) async { _ = phase }

    func stop() async {}

    func agentPresets() async throws -> [AgentPreset] { [] }

    func createSession(preset: String?, workspaceID: String?, cwd: String?) async throws -> String {
        _ = (preset, workspaceID, cwd)
        throw InboxServiceError.offline
    }

    func sendPrompt(sessionID: String, text: String, images: [PromptImage]) async throws {
        _ = (sessionID, text, images)
        throw InboxServiceError.offline
    }

    func createWorkspace(path: String) async throws -> WorkspaceWriteResult {
        _ = path
        throw InboxServiceError.offline
    }

    func deleteWorkspace(path: String) async throws {
        _ = path
        throw InboxServiceError.offline
    }
}

enum InboxServiceError: Error, Equatable, Sendable {
    case missingHost
    case offline
    case unauthorized
    case certificate
    case notSubscribed
    case failed
}

@MainActor @Observable
final class InboxModel {
    let hostID: String
    let autostart: Bool
    private let service: any InboxServing
    private let cache: any InboxCaching
    private let preferences: InboxPreferences
    var onSwitch: ((String) -> Void)?
    var clock: @MainActor () -> Date = { Date() }
    /// 会话删除成功后丢弃该会话的草稿（C02 要求 9）。归档不调用：归档可恢复，草稿保留。
    var discardDrafts: @MainActor (_ hostID: String, _ sessionID: String) -> Void = { _, _ in }

    var computerName = ""
    var link: InboxLink = .checking(nil)
    var sessions: [SessionSummary] = []
    var workspaces: [WorkspaceInfo] = []
    var computers: [InboxComputer] = []
    var filter: InboxListFilter = .all
    var query = ""
    var tokens: [InboxWorkspaceToken] = []
    var workspaceSuggestions: [InboxWorkspaceToken] = []
    var recentSearches: [String] = []
    var phoneAction: InboxPhoneAction?
    var path: [InboxDestination] = []
    /// Wide layout only. Compact navigation still pushes `.session` onto `path`.
    var selectedSessionID: String?
    var renameTarget: SessionSummary?
    var renameDraft = ""
    var deleteTarget: SessionSummary?
    var deleteWorkspacePath: String?
    var approvalTick = 0
    var notice: InboxNotice?
    var searching = false
    var loading = false
    var now = Date()
    var calendar = Calendar.current
    var missingHost = false
    var pushVersion = 0
    var pairedDeviceID: String?
    var starter = ""
    var sharePrefill: SharePrefill?
    var pendingShare: ShareInboxRecord?
    var shareRecents: [ShareRecentSession] = []
    private var archivedIDs: Set<String> = []
    private var pendingArchive: Set<String> = []
    private var deletedIDs: Set<String> = []
    private var searchPayload: InboxSearchPayload?
    private var searchDegraded = false
    private var searchFailed = false
    private var searchGeneration = 0
    private var started = false
    private var refreshing = false
    private var refreshAgain = false

    init(
        hostID: String,
        service: any InboxServing,
        cache: any InboxCaching,
        preferences: InboxPreferences = InboxPreferences(),
        autostart: Bool = true,
        now: Date = Date(),
        calendar: Calendar = .current
    ) {
        self.hostID = hostID
        self.service = service
        self.cache = cache
        self.preferences = preferences
        self.autostart = autostart
        self.now = now
        self.calendar = calendar
        deletedIDs = preferences.deletedIDs(hostID: hostID)
        recentSearches = preferences.recentSearches(hostID: hostID)
        collapsedFolders = preferences.collapsedFolders(hostID: hostID)
        expandedPreviews = preferences.expandedPreviews(hostID: hostID)
        if let saved = preferences.workspace(hostID: hostID) {
            tokens = [InboxWorkspaceToken(path: saved, name: inboxWorkspaceName(saved) ?? saved)]
        }
        if let cached = cache.load(hostID: hostID) {
            sessions = cached.sessions
            archivedIDs = Set(cached.archivedIDs)
            workspaces = cached.workspaces
            computerName = cached.hostName
            link = .checking(cached.route)
        }
        syncSuggestions()
    }

    var actionsEnabled: Bool { link.isOnline }
    var collapsedFolders: Set<String> = []
    var expandedPreviews: Set<String> = []
    var folderPaths: [String] { inboxVisibleWorkspaces(workspaces) }

    func toggleFolder(_ key: String) {
        collapsedFolders.formSymmetricDifference([key])
        preferences.setCollapsedFolders(collapsedFolders, hostID: hostID)
    }

    func togglePreview(_ key: String) {
        expandedPreviews.formSymmetricDifference([key])
        preferences.setExpandedPreviews(expandedPreviews, hostID: hostID)
    }

    var effectiveArchived: Set<String> { archivedIDs.union(pendingArchive) }

    var visibleSessions: [SessionSummary] {
        let base = inboxVisibleSessions(
            sessions, archivedIDs: effectiveArchived, deletedIDs: deletedIDs, now: now)
        guard let workspace = tokens.first?.path else { return base }
        let accounts = workspaces.compactMap { workspace -> InboxWorkspaceAccount? in
            guard let path = workspace.path else { return nil }
            return InboxWorkspaceAccount(path: path, sessionIDs: workspace.sessionIds ?? [])
        }
        return inboxSessions(inWorkspace: workspace, sessions: base, accounts: accounts)
    }

    var archivedSessions: [SessionSummary] {
        sessions.filter { session in
            guard let id = session.sessionId else { return false }
            return effectiveArchived.contains(id) && !deletedIDs.contains(id)
        }
        .sorted { (inboxSessionMillis($0.updatedAt) ?? 0) > (inboxSessionMillis($1.updatedAt) ?? 0) }
    }

    var presentation: InboxPresentation {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty {
            if searchFailed {
                let empty = InboxSearchGroups(titleMatches: [], contentMatches: [])
                return .search(empty, degraded: false, failed: true)
            }
            return .search(searchGroups(trimmed), degraded: searchDegraded, failed: false)
        }
        if visibleSessions.isEmpty {
            if isChecking { return sessions.isEmpty ? .loading : .starters }
            if !link.isOnline { return .offlineEmpty }
            if tokens.first != nil { return .workspaceEmpty }
            return .starters
        }
        return .folders(
            inboxWorkspaceFolders(
                sessions: visibleSessions,
                workspaces: inboxVisibleWorkspaces(workspaces),
                accounts: workspaceAccounts,
                registryReady: !workspaces.isEmpty))
    }

    var workspaceAccounts: [InboxWorkspaceAccount] {
        workspaces.compactMap { workspace in
            guard let path = workspace.path else { return nil }
            return InboxWorkspaceAccount(path: path, sessionIDs: workspace.sessionIds ?? [])
        }
    }

    func start() async {
        guard autostart, !started else { return }
        started = true
        await refresh()
        await withTaskCancellationHandler {
            await withTaskGroup(of: Void.self) { group in
                group.addTask { await self.consumeEvents() }
                group.addTask { await self.consumeNetwork() }
                await group.waitForAll()
            }
        } onCancel: {
            Task { @MainActor in await self.stop() }
        }
    }

    func stop() async {
        started = false
        await service.stop()
    }

    func setPhase(_ phase: AppPhase) async {
        await service.setPhase(phase)
    }

    func refresh() async {
        if refreshing {
            refreshAgain = true
            return
        }
        refreshing = true
        await reload(resetStreams: true)
        refreshing = false
        if refreshAgain {
            refreshAgain = false
            await refresh()
        }
    }

    func applyQuery() async {
        searchGeneration += 1
        let generation = searchGeneration
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            searchPayload = nil
            searchFailed = false
            searchDegraded = false
            searching = false
            if notice == .search { notice = nil }
            return
        }
        searching = true
        do {
            let result = try await service.search(query: trimmed)
            guard generation == searchGeneration else { return }
            searchPayload = result
            searchDegraded = result.degraded
            searchFailed = false
            if notice == .search { notice = nil }
        } catch {
            guard generation == searchGeneration else { return }
            searchPayload = nil
            searchFailed = true
            notice = .search
        }
        searching = false
    }

    /// Snapshot and tests install archive membership without a server round trip.
    func useArchived(_ ids: [String]) {
        archivedIDs = Set(ids)
        pendingArchive.subtract(archivedIDs)
    }

    /// Snapshot and tests install a finished search without opening a connection.
    func useSearchResult(_ payload: InboxSearchPayload?, failed: Bool = false) {
        searchPayload = payload
        searchDegraded = payload?.degraded ?? false
        searchFailed = failed
        searching = false
    }

    func submitSearch() {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        var recent = recentSearches.filter { $0.caseInsensitiveCompare(trimmed) != .orderedSame }
        recent.insert(trimmed, at: 0)
        recentSearches = Array(recent.prefix(8))
        preferences.setRecentSearches(recentSearches, hostID: hostID)
    }

    func setWorkspace(_ path: String?) {
        if let path {
            tokens = [InboxWorkspaceToken(path: path, name: inboxWorkspaceName(path) ?? path)]
        } else {
            tokens = []
        }
        persistWorkspace()
        syncSuggestions()
    }

    func persistWorkspace() {
        preferences.setWorkspace(tokens.first?.path, hostID: hostID)
    }

    func syncSuggestions() {
        let selected = Set(tokens.map(\.path))
        workspaceSuggestions = inboxVisibleWorkspaces(workspaces).filter { !selected.contains($0) }.map { path in
            InboxWorkspaceToken(path: path, name: inboxWorkspaceName(path) ?? path)
        }
    }

    func selectComputer(_ id: String) {
        if id == hostID {
            path.append(.computer(id))
        } else {
            onSwitch?(id)
        }
    }

    func computerRows(copy: InboxCopy) -> [InboxComputerRow] {
        var rows = computers
        if !rows.contains(where: { $0.id == hostID }) {
            rows.insert(InboxComputer(id: hostID, name: displayName), at: 0)
        }
        return rows.map { computer in
            let title: String
            if computer.id == hostID {
                title = "\(computer.name.isEmpty ? displayName : computer.name) · \(copy.linkWord(link))"
            } else {
                title = computer.name
            }
            return InboxComputerRow(id: computer.id, title: title, current: computer.id == hostID)
        }
    }

    func deleteWorkspace(_ path: String) async {
        guard actionsEnabled else { return }
        do {
            try await service.deleteWorkspace(path: path)
            deleteWorkspacePath = nil
            workspaces.removeAll { inboxNormalizeWorkspacePath($0.path ?? "") == inboxNormalizeWorkspacePath(path) }
            if tokens.first?.path == path { setWorkspace(nil) }
        } catch {
            notice = .delete
        }
    }

    func openNewTask(workspace path: String) {
        setWorkspace(path)
        openNewTask()
    }

    func openNewTask(_ text: String = "") {
        starter = text
        path.append(.newTask(text))
    }

    func loadAgentPresets() async -> [AgentPreset] {
        (try? await service.agentPresets()) ?? []
    }

    func createNewTaskSession(preset: String?, workspaceID: String?, cwd: String?) async throws -> String {
        try await service.createSession(preset: preset, workspaceID: workspaceID, cwd: cwd)
    }

    func sendNewTask(_ text: String, images: [PromptImage] = [], sessionID: String) async throws {
        try await service.sendPrompt(sessionID: sessionID, text: text, images: images)
    }

    func submitWorkspace(_ path: String) async throws -> WorkspaceWriteResult {
        try await service.createWorkspace(path: path)
    }

    func open(_ session: SessionSummary) {
        guard let id = session.sessionId else { return }
        path.append(.session(id))
    }

    /// Opens a notification. A missing session refreshes once; if it is still
    /// absent, navigation returns home and shows a notice. It never approves.
    func openPush(deviceID: String, sessionID: String) async {
        let known = Set(sessions.compactMap(\.sessionId))
        let first = PushOpenRouter.route(
            deviceID: deviceID, sessionID: sessionID, pairedDeviceID: pairedDeviceID, knownSessions: known)
        switch first {
        case .session(let id):
            path.append(.session(id))
        case .refresh:
            await refresh()
            let refreshed = Set(sessions.compactMap(\.sessionId))
            let second = PushOpenRouter.route(
                deviceID: deviceID, sessionID: sessionID, pairedDeviceID: pairedDeviceID,
                knownSessions: refreshed, refreshed: true)
            if case .session(let id) = second {
                path.append(.session(id))
            } else {
                path.removeAll()
                selectedSessionID = nil
                notice = .pushMissing
            }
        case .homeMissing:
            path.removeAll()
            selectedSessionID = nil
            notice = .pushMissing
        }
    }

    /// Reads one shared item and shows the picker. It does not send.
    func receiveShare(url: URL, store: ShareGroupStore?) {
        guard let id = ShareInbox.id(from: url), let store, let record = store.readRecord(id: id) else { return }
        shareRecents = store.readRecents()
        pendingShare = record
    }

    func cancelShare(store: ShareGroupStore?) {
        if let id = pendingShare?.id { store?.consume(id: id) }
        pendingShare = nil
    }

    /// The selected target only receives a draft. Sending stays on the composer button.
    func acceptShare(_ target: ShareTarget, store: ShareGroupStore?) {
        guard let record = pendingShare else { return }
        let image = store.flatMap { $0.imageData(for: record) }.flatMap { bytes in
            if case .image(let prompt) = classifyPromptAttachment(
                bytes: bytes, declaredMediaType: nil, existingCount: 0)
            {
                return prompt
            }
            return nil
        }
        sharePrefill = ShareInbox.prefill(record: record, image: image, target: target)
        store?.consume(id: record.id)
        pendingShare = nil
        switch target {
        case .newTask:
            starter = record.text
            path.append(.newTask(record.text))
        case .session(let id):
            path.append(.session(id))
        }
    }

    func takeSharePrefill(for target: ShareTarget) -> SharePrefill? {
        guard sharePrefill?.target == target else { return nil }
        let value = sharePrefill
        sharePrefill = nil
        return value
    }

    /// Titles only. The extension never receives tokens, paths, or message text.
    private func publishShareRecents() {
        guard let store = ShareGroupStore.live() else { return }
        let recents = ShareSessionSource.recent(from: sessions)
        try? store.writeRecents(recents)
    }

    func askRename(_ session: SessionSummary) {
        renameTarget = session
        let title = inboxDisplayTitle(session.title).trimmingCharacters(in: .whitespacesAndNewlines)
        renameDraft = title
    }

    func commitRename() async {
        guard let session = renameTarget, let id = session.sessionId else { return }
        let title = renameDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        renameTarget = nil
        guard !title.isEmpty else { return }
        do {
            try await service.rename(sessionID: id, title: title)
            if let index = sessions.firstIndex(where: { $0.sessionId == id }) {
                sessions[index].title = title
            }
        } catch {
            notice = .rename
        }
    }

    func askDelete(_ session: SessionSummary) {
        deleteTarget = session
    }

    func commitDelete() async {
        guard let session = deleteTarget, let id = session.sessionId else { return }
        deleteTarget = nil
        await hide(id, noticeOnFailure: .delete, deleted: true)
    }

    func archive(_ session: SessionSummary) async {
        guard let id = session.sessionId else { return }
        await hide(id, noticeOnFailure: .archive, deleted: false)
    }

    func fork(_ session: SessionSummary) async {
        guard let id = session.sessionId else { return }
        do {
            _ = try await service.fork(sessionID: id)
            await refresh()
        } catch {
            notice = .fork
        }
    }

    func decide(allow: Bool) async {
        guard link.isOnline, case .approval(let sessionID, let approvalID, _) = phoneAction else { return }
        do {
            try await service.decide(
                sessionID: sessionID, approvalID: approvalID, outcome: allow ? "allowed-once" : "rejected")
            approvalTick += 1
            await refresh()
        } catch {
            notice = .approval
        }
    }

    func apply(_ signal: InboxLiveSignal) async {
        switch signal {
        case .state(let event):
            let millis = Int(now.timeIntervalSince1970 * 1000)
            sessions = inboxApplying(event, to: sessions, nowMillis: millis)
            if let seq = event.seq { await service.commitHostEvent(seq) }
            let actionable = inboxActionableSessionID(visibleSessions)
            if event.sessionId == phoneAction?.sessionID || event.sessionId == actionable {
                await refreshAction()
            }
        case .resync:
            await reload(resetStreams: false)
            await service.resumeHostEvents()
        case .unauthorized:
            link = .offline
            notice = .unauthorized
        }
    }

    var displayName: String {
        computerName.isEmpty ? hostID : computerName
    }

    var lastOnlineAt: Date? {
        preferences.lastOnline(hostID: hostID)
    }

    private var isChecking: Bool {
        if case .checking = link { return true }
        return false
    }

    private func searchGroups(_ query: String) -> InboxSearchGroups {
        let items = searchPayload?.items ?? []
        let ids = items.compactMap(\.sessionId)
        let snippets = Dictionary(
            uniqueKeysWithValues: items.compactMap { item -> (String, String)? in
                guard let id = item.sessionId, let snippet = item.snippet, !snippet.isEmpty else { return nil }
                return (id, snippet)
            })
        let candidates = inboxSearchCandidates(visibleSessions, needle: query, serverIDs: ids)
        return inboxSearchGroups(sessions: candidates, needle: query, snippets: snippets)
    }

    private func consumeEvents() async {
        let stream = await service.openEvents()
        for await signal in stream {
            if Task.isCancelled { return }
            await apply(signal)
        }
    }

    private func consumeNetwork() async {
        let stream = await service.networkChanges()
        for await _ in stream {
            if Task.isCancelled { return }
            await refresh()
        }
    }

    private func reload(resetStreams: Bool) async {
        now = clock()
        computers = await service.computers()
        do {
            let payload = try await service.load(resetStreams: resetStreams)
            sessions = payload.sessions
            publishShareRecents()
            let serverArchived = Set(payload.archivedIDs)
            pendingArchive.subtract(serverArchived)
            archivedIDs = serverArchived
            workspaces = payload.workspaces
            if !payload.hostName.isEmpty { computerName = payload.hostName }
            link = .online(payload.route)
            pushVersion = payload.pushVersion
            pairedDeviceID = payload.pairedDeviceID
            loading = false
            if notice == .unauthorized || notice == .certificate || notice == .load { notice = nil }
            preferences.setLastOnline(now, hostID: hostID)
            cache.save(
                hostID: hostID,
                snapshot: InboxCacheSnapshot(
                    sessions: sessions, archivedIDs: payload.archivedIDs, workspaces: workspaces,
                    hostName: computerName, route: payload.route, eventsEnabled: payload.eventsEnabled))
            syncSuggestions()
            await refreshAction()
            if !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { await applyQuery() }
        } catch {
            applyFailure(error)
        }
    }

    private func applyFailure(_ error: any Error) {
        loading = false
        let mapped = error as? InboxServiceError ?? .failed
        switch mapped {
        case .missingHost:
            missingHost = true
        case .unauthorized:
            link = .offline
            notice = .unauthorized
        case .certificate:
            link = .offline
            notice = .certificate
        case .offline, .failed, .notSubscribed:
            link = .offline
            notice = sessions.isEmpty ? .load : nil
        }
    }

    private func refreshAction() async {
        guard link.isOnline else { return }
        guard let id = inboxActionableSessionID(visibleSessions) else {
            phoneAction = nil
            return
        }
        do {
            let response = try await service.requests(sessionID: id)
            phoneAction = inboxPhoneAction(sessionID: id, response: response)
        } catch {
            phoneAction = nil
        }
    }

    private func hide(_ id: String, noticeOnFailure: InboxNotice, deleted: Bool) async {
        do {
            try await service.archive(sessionID: id)
            pendingArchive.insert(id)
            if deleted {
                deletedIDs.insert(id)
                preferences.setDeletedIDs(deletedIDs, hostID: hostID)
                discardDrafts(hostID, id)
            }
            if phoneAction?.sessionID == id { phoneAction = nil }
            if notice == noticeOnFailure { notice = nil }
        } catch {
            notice = noticeOnFailure
        }
    }
}
