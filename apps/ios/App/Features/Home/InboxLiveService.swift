import DLModels
import DLNet
import DLSecurity
import Foundation

actor InboxLiveService: InboxServing {
    private let hostID: String
    private let store: HostStore
    private let routes: RouteSelector
    private let gate = ForegroundGate()
    private var client: HostClient?
    private var eventsEnabled = false
    private var hostSSE: SSEClient?
    private var hostPump: Task<Void, Never>?
    private var hostGeneration = 0
    private var eventContinuation: AsyncStream<InboxLiveSignal>.Continuation?
    private var sessionSSE: SSEClient?
    private var sessionPump: Task<Void, Never>?
    private var subscribedSessionID: String?
    private var sessionReady = false
    private var sessionFailed = false
    private var networkSource: SystemNetworkPathEventSource?
    private var networkContinuation: AsyncStream<Void>.Continuation?

    init(hostID: String, store: HostStore = HostStore(), routes: RouteSelector = RouteSelector()) {
        self.hostID = hostID
        self.store = store
        self.routes = routes
    }

    func load(resetStreams: Bool) async throws -> InboxPayload {
        guard let host = await store.get(hostId: hostID) else { throw InboxServiceError.missingHost }
        guard let token = await store.token(for: hostID), !token.isEmpty else { throw InboxServiceError.missingHost }
        if !resetStreams, let client {
            return try await fetch(client, host: host, route: Self.routeKind(client.baseURL, host: host))
        }
        await stopHostPump()
        await stopSession()
        let selection = await routes.select(key: hostID, candidates: RouteSelector.directCandidates(for: host)) {
            address in
            await Self.probe(address: address, fingerprint: host.certFingerprint)
        }
        guard case .direct(let address) = selection, let base = URL(string: address) else {
            await routes.forget(key: hostID)
            throw InboxServiceError.offline
        }
        let http = HostClient(baseURL: base, token: token, expectedFingerprint: host.certFingerprint)
        do {
            let payload = try await fetch(http, host: host, route: Self.routeKind(base, host: host))
            client = http
            eventsEnabled = payload.eventsEnabled
            await routes.noteSuccess(key: hostID, address: address)
            if eventContinuation != nil { await restartHostPump() }
            return payload
        } catch {
            await routes.forget(key: hostID)
            throw error
        }
    }

    func search(query: String) async throws -> InboxSearchPayload {
        let http = try requireClient()
        do {
            let response = try await http.get(
                SessionSearchResponse.self, path: "/dsh-link/mobile/sessions/search", query: ["q": query])
            return InboxSearchPayload(items: response.items ?? [], degraded: response.degraded == true)
        } catch {
            throw Self.map(error)
        }
    }

    func requests(sessionID: String) async throws -> RequestsSnapshotResponse {
        let http = try requireClient()
        do {
            return try await http.get(
                RequestsSnapshotResponse.self, path: try sessionPath(sessionID, "/requests"))
        } catch {
            throw Self.map(error)
        }
    }

    func rename(sessionID: String, title: String) async throws {
        let http = try requireClient()
        do {
            let response = try await http.post(
                InboxAck.self, path: try sessionPath(sessionID, "/rename"), json: InboxTitleBody(title: title))
            if response.ok == false { throw InboxServiceError.failed }
        } catch let error as InboxServiceError {
            throw error
        } catch {
            throw Self.map(error)
        }
    }

    func fork(sessionID: String) async throws -> String {
        let http = try requireClient()
        do {
            let response = try await http.post(
                InboxAck.self, path: try sessionPath(sessionID, "/fork"), json: InboxEmptyBody())
            guard response.ok != false, let id = response.sessionId, !id.isEmpty else {
                throw InboxServiceError.failed
            }
            return id
        } catch let error as InboxServiceError {
            throw error
        } catch {
            throw Self.map(error)
        }
    }

    func archive(sessionID: String) async throws {
        let http = try requireClient()
        do {
            let response = try await http.post(
                InboxAck.self, path: try sessionPath(sessionID, "/archive"), json: InboxEmptyBody())
            if response.ok == false { throw InboxServiceError.failed }
        } catch let error as InboxServiceError {
            throw error
        } catch {
            throw Self.map(error)
        }
    }

    func decide(sessionID: String, approvalID: String, outcome: String) async throws {
        try await ensureSubscribed(sessionID)
        let http = try requireClient()
        do {
            let response = try await http.post(
                RequestSubmitResponse.self,
                path: try sessionPath(sessionID, "/approval"),
                json: InboxApprovalBody(approvalId: approvalID, outcome: outcome))
            if response.ok == false && response.accepted == false && response.alreadySettled != true {
                throw InboxServiceError.failed
            }
        } catch let error as InboxServiceError {
            throw error
        } catch {
            throw Self.map(error)
        }
    }

    func computers() async -> [InboxComputer] {
        await store.all().map { InboxComputer(id: $0.hostId, name: $0.name) }
    }

    func networkChanges() -> AsyncStream<Void> {
        let (stream, continuation) = AsyncStream.makeStream(of: Void.self, bufferingPolicy: .unbounded)
        networkContinuation?.finish()
        networkContinuation = continuation
        let source = SystemNetworkPathEventSource()
        networkSource?.cancel()
        networkSource = source
        let selector = routes
        Task {
            for await event in source.events() {
                if Task.isCancelled { break }
                if case .changed = event {
                    await selector.onNetworkChanged()
                    continuation.yield(())
                }
            }
            continuation.finish()
        }
        return stream
    }

    func openEvents() -> AsyncStream<InboxLiveSignal> {
        let (stream, continuation) = AsyncStream.makeStream(
            of: InboxLiveSignal.self, bufferingPolicy: .unbounded)
        eventContinuation?.finish()
        eventContinuation = continuation
        Task { await self.restartHostPump() }
        return stream
    }

    func commitHostEvent(_ seq: Int) async {
        await hostSSE?.commit(seq)
    }

    func resumeHostEvents() async {
        await hostSSE?.resumeFromSnapshot(0)
    }

    func setPhase(_ phase: AppPhase) {
        gate.update(phase)
        if phase == .background { sessionReady = false }
    }

    func agentPresets() async throws -> [AgentPreset] {
        let http = try requireClient()
        let response = try await http.get(AgentPresetListResponse.self, path: "/dsh-link/mobile/agent-presets")
        return response.presets ?? []
    }

    func createSession(preset: String?, workspaceID: String?, cwd: String?) async throws -> String {
        let http = try requireClient()
        let response = try await http.post(
            InboxAck.self, path: "/dsh-link/mobile/sessions",
            json: SessionCreateBody(agentPreset: preset, workspaceId: workspaceID, cwd: workspaceID == nil ? cwd : nil))
        guard response.ok != false, let id = response.sessionId, !id.isEmpty else { throw InboxServiceError.failed }
        return id
    }

    func sendPrompt(sessionID: String, text: String) async throws {
        let http = try requireClient()
        _ = try await http.postJSON(
            path: try sessionPath(sessionID, "/prompt"), json: NewTaskPromptBody(text: text, mode: "queue"))
    }

    func createWorkspace(path: String) async throws -> WorkspaceWriteResult {
        let http = try requireClient()
        let data = try await http.postJSON(
            path: "/dsh-link/mobile/workspaces", json: WorkspaceCreateBody(path: path))
        let decoded = try JSONDecoder().decode(WorkspaceWriteBody.self, from: data)
        if decoded.pending == true {
            return .pending(decoded.path ?? path)
        }
        guard let workspace = decoded.workspace else { throw InboxServiceError.failed }
        return .created(workspace)
    }

    func stop() async {
        networkSource?.cancel()
        networkSource = nil
        networkContinuation?.finish()
        networkContinuation = nil
        eventContinuation?.finish()
        eventContinuation = nil
        await stopHostPump()
        await stopSession()
    }

    private func fetch(_ http: HostClient, host: PairedHost, route: InboxRouteKind) async throws -> InboxPayload {
        let listed: SessionListResponse
        do {
            listed = try await http.get(SessionListResponse.self, path: "/dsh-link/mobile/sessions")
        } catch {
            throw Self.map(error)
        }
        let bootstrap = try? await http.get(BootstrapResponse.self, path: "/dsh-link/mobile/bootstrap")
        let workspaceList = try? await http.get(WorkspaceListResponse.self, path: "/dsh-link/mobile/workspaces")
        var archived = Set(listed.archivedSessionIds ?? [])
        archived.formUnion(workspaceList?.archivedSessionIds ?? [])
        return InboxPayload(
            sessions: listed.sessions ?? [],
            archivedIDs: Array(archived),
            workspaces: workspaceList?.workspaces ?? [],
            hostName: bootstrap?.host?.name ?? host.name,
            route: route,
            eventsEnabled: bootstrap?.capabilities?.events?.host == true)
    }

    private func requireClient() throws -> HostClient {
        guard let client else { throw InboxServiceError.offline }
        return client
    }

    private func ensureSubscribed(_ sessionID: String) async throws {
        if subscribedSessionID == sessionID, !sessionFailed {
            if sessionReady { return }
            if await waitUntilReady() { return }
            throw InboxServiceError.notSubscribed
        }
        await stopSession()
        let http = try requireClient()
        let stream = SSEClient(
            transport: URLSessionSSETransport(session: http.session),
            makeRequest: { cursor in Self.sessionRequest(client: http, sessionID: sessionID, cursor: cursor) })
        stream.follow(gate)
        sessionSSE = stream
        subscribedSessionID = sessionID
        sessionReady = false
        sessionFailed = false
        let events = stream.events
        await stream.start()
        sessionPump = Task { await self.drainSession(events) }
        if await waitUntilReady() { return }
        throw InboxServiceError.notSubscribed
    }

    private func drainSession(_ events: AsyncStream<SSEClient.Output>) async {
        for await output in events {
            switch output {
            case .connected:
                sessionReady = true
            case .terminated:
                sessionReady = false
                sessionFailed = true
            case .retryScheduled:
                sessionReady = false
            default:
                break
            }
        }
    }

    private func waitUntilReady() async -> Bool {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(2))
        while !sessionReady && !sessionFailed {
            if clock.now >= deadline { return false }
            try? await Task.sleep(for: .milliseconds(20))
        }
        return sessionReady
    }

    private func restartHostPump() async {
        await stopHostPump()
        guard eventsEnabled, let client, eventContinuation != nil else { return }
        let generation = hostGeneration
        let http = client
        hostPump = Task { await self.runHost(http, generation: generation) }
    }

    private func runHost(_ http: HostClient, generation: Int) async {
        guard generation == hostGeneration else { return }
        let stream = SSEClient(
            transport: URLSessionSSETransport(session: http.session),
            makeRequest: { cursor in Self.hostRequest(client: http, cursor: cursor) })
        stream.follow(gate)
        hostSSE = stream
        let events = stream.events
        await stream.start()
        for await output in events {
            if generation != hostGeneration { break }
            handleHost(output)
        }
    }

    private func handleHost(_ output: SSEClient.Output) {
        switch output {
        case .event(let event):
            guard event.event == "session/state",
                let decoded = try? JSONDecoder().decode(HostSessionStateEvent.self, from: Data(event.data.utf8))
            else { return }
            eventContinuation?.yield(.state(decoded))
        case .resyncRequired:
            eventContinuation?.yield(.resync)
        case .terminated(.unauthorized):
            eventContinuation?.yield(.unauthorized)
        default:
            break
        }
    }

    private func stopHostPump() async {
        hostGeneration += 1
        hostPump?.cancel()
        hostPump = nil
        await hostSSE?.stop()
        hostSSE = nil
    }

    private func stopSession() async {
        sessionPump?.cancel()
        sessionPump = nil
        await sessionSSE?.stop()
        sessionSSE = nil
        subscribedSessionID = nil
        sessionReady = false
        sessionFailed = false
    }

    private func sessionPath(_ id: String, _ suffix: String) throws -> String {
        guard !id.isEmpty, id.rangeOfCharacter(from: CharacterSet(charactersIn: "/?#")) == nil else {
            throw InboxServiceError.failed
        }
        return "/dsh-link/mobile/sessions/\(id)\(suffix)"
    }

    private static func routeKind(_ base: URL, host: PairedHost) -> InboxRouteKind {
        let selected = identity(base.absoluteString)
        let tail = host.tailnetUrl.map(identity) ?? ""
        let primary = identity(host.primaryUrl)
        if !tail.isEmpty, selected == tail, selected != primary { return .remote }
        return .local
    }

    private static func identity(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            .lowercased()
    }

    /// Any HTTP response means the address answered. Pin failure and transport failure do not.
    /// No token is sent. Slightly wider than a TLS-only handshake: there is no handshake-only API.
    private static func probe(address: String, fingerprint: String) async -> Bool {
        guard let url = URL(string: address) else { return false }
        let delegate = PinnedSessionDelegate(expectedFingerprint: fingerprint)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 1.2
        configuration.timeoutIntervalForResource = 1.2
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 1.2
        let before = delegate.pinFailureCount
        do {
            let (_, response) = try await session.data(for: request)
            session.finishTasksAndInvalidate()
            return response is HTTPURLResponse && delegate.pinFailureCount == before
        } catch {
            session.invalidateAndCancel()
            return false
        }
    }

    private static func hostRequest(client: HostClient, cursor: Int) -> URLRequest {
        let url = HostClient.url(baseURL: client.baseURL, path: "/dsh-link/mobile/events", query: [:])
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        if cursor > 0 {
            request.setValue(String(cursor), forHTTPHeaderField: "Last-Event-ID")
        }
        request.setValue(client.token, forHTTPHeaderField: HostClient.tokenHeaderName)
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        return request
    }

    private static func sessionRequest(client: HostClient, sessionID: String, cursor: Int) -> URLRequest {
        let url = HostClient.url(
            baseURL: client.baseURL,
            path: "/dsh-link/mobile/sessions/\(sessionID)/stream",
            query: ["afterSeq": String(cursor), "caps": "sync2,multiQuestion,requestState"])
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue(client.token, forHTTPHeaderField: HostClient.tokenHeaderName)
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        return request
    }

    private static func map(_ error: any Error) -> InboxServiceError {
        guard let error = error as? HostClientError else { return .offline }
        switch error {
        case .unauthorized: return .unauthorized
        case .certificateChanged: return .certificate
        case .transport: return .offline
        default: return .failed
        }
    }
}

private struct InboxEmptyBody: Encodable {}

private struct InboxTitleBody: Encodable {
    var title: String
}

private struct InboxApprovalBody: Encodable {
    var approvalId: String
    var outcome: String
}

private struct InboxAck: Decodable {
    var ok: Bool?
    var sessionId: String?
}

private struct SessionCreateBody: Encodable {
    var agentPreset: String?
    var workspaceId: String?
    var cwd: String?
}

private struct NewTaskPromptBody: Encodable {
    var text: String
    var mode: String
}

private struct WorkspaceCreateBody: Encodable {
    var path: String
}

private struct WorkspaceWriteBody: Decodable {
    var workspace: WorkspaceInfo?
    var pending: Bool?
    var path: String?
}
