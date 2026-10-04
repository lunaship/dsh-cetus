import DLCore
import DLModels
import DLNet
import DLSecurity
import Foundation

actor ConversationLiveService: ConversationServing {
    private let hostID: String
    private let store: HostStore
    private let routes: RouteSelector
    private let gate = ForegroundGate()
    private var client: HostClient?
    private var sse: SSEClient?
    private var pump: Task<Void, Never>?
    private var continuation: AsyncStream<ConversationSignal>.Continuation?

    init(hostID: String, store: HostStore = HostStore(), routes: RouteSelector = RouteSelector()) {
        self.hostID = hostID
        self.store = store
        self.routes = routes
    }

    func history(sessionID: String) async throws -> HistoryResponse {
        let http = try await connect()
        do {
            return try await http.get(HistoryResponse.self, path: try sessionPath(sessionID, "/history"))
        } catch {
            throw Self.map(error)
        }
    }

    func requests(sessionID: String) async throws -> RequestsSnapshotResponse? {
        let http = try await connect()
        return try await http.get(RequestsSnapshotResponse.self, path: try sessionPath(sessionID, "/requests"))
    }

    func detections() async throws -> PreviewDetectionsResponse? {
        let http = try await connect()
        return try await http.get(PreviewDetectionsResponse.self, path: "/dsh-link/mobile/preview-detections")
    }

    func open(sessionID: String, afterSeq: Int) async throws -> AsyncStream<ConversationSignal> {
        let http = try await connect()
        await stopStream()
        let (stream, continuation) = AsyncStream.makeStream(
            of: ConversationSignal.self, bufferingPolicy: .unbounded)
        self.continuation = continuation
        let sse = SSEClient(
            transport: URLSessionSSETransport(session: http.session),
            makeRequest: { cursor in Self.streamRequest(client: http, sessionID: sessionID, cursor: cursor) })
        await sse.commit(afterSeq)
        sse.follow(gate)
        self.sse = sse
        await sse.start()
        pump = Task { await self.forward(sse) }
        return stream
    }

    func commit(_ seq: Int) async {
        await sse?.recordReceived(seq)
        await sse?.commit(seq)
    }

    func resume(after seq: Int) async {
        await sse?.resumeFromSnapshot(seq)
    }

    func setPhase(_ phase: AppPhase) async {
        gate.update(phase)
    }

    func stop() async {
        await stopStream()
    }

    private func connect() async throws -> HostClient {
        if let client { return client }
        guard let host = await store.get(hostId: hostID) else { throw ConversationServiceError.missingHost }
        guard let token = await store.token(for: hostID), !token.isEmpty else {
            throw ConversationServiceError.missingHost
        }
        let selection = await routes.select(key: hostID, candidates: RouteSelector.directCandidates(for: host)) {
            address in
            await Self.probe(address: address, fingerprint: host.certFingerprint)
        }
        guard case .direct(let address) = selection, let base = URL(string: address) else {
            await routes.forget(key: hostID)
            throw ConversationServiceError.offline
        }
        let http = HostClient(baseURL: base, token: token, expectedFingerprint: host.certFingerprint)
        client = http
        await routes.noteSuccess(key: hostID, address: address)
        return http
    }

    private func forward(_ sse: SSEClient) async {
        for await output in sse.events {
            switch output {
            case .event(let event):
                emit(event)
            case .connected:
                continuation?.yield(.connection(.connected))
            case .retryScheduled:
                continuation?.yield(.connection(.reconnecting))
            case .resyncRequired:
                continuation?.yield(.resync)
            case .terminated(let failure):
                if case .stopped = failure { break }
                continuation?.yield(.failed)
            }
        }
    }

    private func emit(_ event: SSEEvent) {
        let data = Data(event.data.utf8)
        let name = event.event ?? "message"
        if ["ready", "stats", "question", "question-resolved"].contains(name),
            let value = try? JSONDecoder().decode(JSONValue.self, from: data)
        {
            continuation?.yield(.statusEvent(name: name, data: value))
        }
        if event.event == "stats", let stats = try? JSONDecoder().decode(HistoryStats.self, from: data) {
            continuation?.yield(.stats(stats))
            return
        }
        guard name == "message", let envelope = try? JSONDecoder().decode(SessionEventEnvelope.self, from: data) else {
            return
        }
        continuation?.yield(
            .frame(
                StreamFrame(
                    seq: envelope.seq ?? 0, type: envelope.type ?? "", time: envelope.time ?? 0,
                    data: envelope.data ?? .null)))
    }

    private func stopStream() async {
        pump?.cancel()
        pump = nil
        await sse?.stop()
        sse = nil
        continuation?.finish()
        continuation = nil
    }

    private func sessionPath(_ id: String, _ suffix: String) throws -> String {
        guard !id.isEmpty, id.rangeOfCharacter(from: CharacterSet(charactersIn: "/?#")) == nil else {
            throw ConversationServiceError.failed
        }
        return "/dsh-link/mobile/sessions/\(id)\(suffix)"
    }

    private static func streamRequest(client: HostClient, sessionID: String, cursor: Int) -> URLRequest {
        let url = HostClient.url(
            baseURL: client.baseURL, path: "/dsh-link/mobile/sessions/\(sessionID)/stream",
            query: ["afterSeq": String(cursor), "caps": "sync2,multiQuestion,requestState"])
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue(client.token, forHTTPHeaderField: HostClient.tokenHeaderName)
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        return request
    }

    /// 401 只上报。这里不删除凭据。
    private static func map(_ error: any Error) -> ConversationServiceError {
        guard let error = error as? HostClientError else { return .offline }
        switch error {
        case .unauthorized: return .unauthorized
        case .certificateChanged: return .certificate
        case .transport: return .offline
        default: return .failed
        }
    }

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
}
