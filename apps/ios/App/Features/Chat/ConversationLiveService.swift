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
    private let backgroundSend: BackgroundSendCover
    private var phase: AppPhase = .active
    private var client: HostClient?
    private var sse: SSEClient?
    private var pump: Task<Void, Never>?
    private var continuation: AsyncStream<ConversationSignal>.Continuation?

    init(
        hostID: String,
        store: HostStore = HostStore(),
        routes: RouteSelector = RouteSelector(),
        backgroundTasks: any BackgroundTaskHandling = NoopBackgroundTasks()
    ) {
        self.hostID = hostID
        self.store = store
        self.routes = routes
        self.backgroundSend = BackgroundSendCover(tasks: backgroundTasks)
    }

    func history(sessionID: String, beforeSeq: Int? = nil) async throws -> HistoryResponse {
        let http = try await connect()
        var query: [String: String] = [:]
        if let beforeSeq, beforeSeq > 0 { query["beforeSeq"] = String(beforeSeq) }
        do {
            return try await http.get(
                HistoryResponse.self, path: try sessionPath(sessionID, "/history"), query: query)
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
        self.phase = phase
        gate.update(phase)
        // SSE 由 gate 立即断开。这里只覆盖还没写完的 HTTP 请求。
        backgroundSend.coverIfNeeded(phase: phase)
    }

    func stop() async {
        await stopStream()
    }

    func sendPrompt(sessionID: String, text: String, images: [PromptImage]) async throws {
        backgroundSend.beginSend(phase: phase)
        defer { backgroundSend.finishSend() }
        let http = try await connect()
        let body = try encodePromptRequest(text: text, mode: "queue", images: images)
        do {
            _ = try await http.postJSONData(path: try sessionPath(sessionID, "/prompt"), body: body)
        } catch {
            throw Self.map(error)
        }
    }

    func submitApproval(sessionID: String, approvalID: String, outcome: String) async throws {
        let http = try await connect()
        let body = ApprovalBody(approvalId: approvalID, outcome: outcome)
        do {
            _ = try await http.post(
                RequestSubmitResponse.self, path: try sessionPath(sessionID, "/approval"), json: body)
        } catch {
            throw Self.map(error)
        }
    }

    func submitQuestion(sessionID: String, rpcID: String, answer: QuestionAnswerBody) async throws {
        let http = try await connect()
        let body = QuestionBody(rpcId: rpcID, answer: answer)
        do {
            _ = try await http.post(
                RequestSubmitResponse.self, path: try sessionPath(sessionID, "/question"), json: body)
        } catch {
            throw Self.map(error)
        }
    }

    func models(sessionID: String) async throws -> SessionModelsResponse {
        let http = try await connect()
        do {
            return try await http.get(
                SessionModelsResponse.self, path: "/dsh-link/mobile/models", query: ["sessionId": sessionID])
        } catch {
            throw Self.map(error)
        }
    }

    func selectModel(sessionID: String, provider: String, model: String, effort: String?) async throws {
        let http = try await connect()
        do {
            _ = try await http.postJSON(
                path: try sessionPath(sessionID, "/model"),
                json: ModelBody(provider: provider, model: model, reasoningEffort: effort))
        } catch {
            throw Self.map(error)
        }
    }

    func setPermission(sessionID: String, preset: String) async throws {
        let http = try await connect()
        do {
            _ = try await http.postJSON(
                path: try sessionPath(sessionID, "/permission"), json: PresetBody(preset: preset))
        } catch {
            throw Self.map(error)
        }
    }

    func renameSession(sessionID: String, title: String) async throws {
        let http = try await connect()
        do {
            _ = try await http.postJSON(path: try sessionPath(sessionID, "/rename"), json: TitleBody(title: title))
        } catch {
            throw Self.map(error)
        }
    }

    func forkSession(sessionID: String) async throws -> String? {
        let http = try await connect()
        do {
            let response = try await http.post(
                ForkBodyResponse.self, path: try sessionPath(sessionID, "/fork"), json: EmptyJSON())
            return response.sessionId
        } catch {
            throw Self.map(error)
        }
    }

    func schedules(sessionID: String, all: Bool) async throws -> [ScheduleTask] {
        let http = try await connect()
        do {
            let response =
                if all {
                    try await http.get(ScheduleListResponse.self, path: "/dsh-link/mobile/schedules")
                } else {
                    try await http.get(ScheduleListResponse.self, path: try sessionPath(sessionID, "/schedules"))
                }
            return response.items ?? []
        } catch {
            throw Self.map(error)
        }
    }

    func editGoal(sessionID: String, refID: String, revision: Int, objective: String, rounds: Int) async throws {
        let http = try await connect()
        let body = GoalEditBody(
            ref: GoalRefBody(id: refID, revision: revision), objective: objective, maxGoalRounds: rounds)
        do {
            _ = try await http.postJSON(path: try sessionPath(sessionID, "/goal/edit"), json: body)
        } catch {
            throw Self.map(error)
        }
    }

    func pauseGoal(sessionID: String, refID: String, revision: Int, resume: Bool) async throws {
        let http = try await connect()
        let suffix = resume ? "/goal/resume" : "/goal/pause"
        do {
            _ = try await http.postJSON(
                path: try sessionPath(sessionID, suffix),
                json: GoalClearBody(ref: GoalRefBody(id: refID, revision: revision)))
        } catch {
            throw Self.map(error)
        }
    }

    func clearGoal(sessionID: String, refID: String, revision: Int) async throws {
        let http = try await connect()
        do {
            _ = try await http.postJSON(
                path: try sessionPath(sessionID, "/goal/clear"),
                json: GoalClearBody(ref: GoalRefBody(id: refID, revision: revision)))
        } catch {
            throw Self.map(error)
        }
    }

    /// C08：改动摘要（`GET /sessions/:id/changes?seq=`）。
    /// 主机不支持时返回 404 `changes_unsupported` → 映射为 nil（App 出空态，不造假数据）。
    func changesSummary(sessionID: String, seq: Int) async throws -> ChangesSummary? {
        let http = try await connect()
        do {
            let response = try await http.get(
                ChangesSummaryResponse.self,
                path: try sessionPath(sessionID, "/changes"),
                query: ["seq": String(seq)])
            guard let files = response.files, !files.isEmpty else { return nil }
            return ChangesSummary(
                turn: response.turn, total: response.total,
                added: response.added, deleted: response.deleted, files: files)
        } catch let error as HostClientError where error == .capabilityMissing {
            // 404：主机不支持改动 / 摘要已过期 → 出空态，不造假数据。
            return nil
        } catch {
            throw Self.map(error)
        }
    }

    /// C08：单文件对比（`GET /sessions/:id/changes/diff?seq=&index=`）。
    func changesDiff(sessionID: String, seq: Int, index: Int) async throws -> ChangesDiffResponse? {
        let http = try await connect()
        do {
            return try await http.get(
                ChangesDiffResponse.self,
                path: try sessionPath(sessionID, "/changes/diff"),
                query: ["seq": String(seq), "index": String(index)])
        } catch let error as HostClientError where error == .capabilityMissing {
            return nil
        } catch {
            throw Self.map(error)
        }
    }

    /// C09：工作区文件树（`GET /sessions/:id/tree?path=`）。
    func tree(sessionID: String, path: String) async throws -> TreeResponse? {
        let http = try await connect()
        var query: [String: String] = [:]
        if !path.isEmpty { query["path"] = path }
        do {
            return try await http.get(TreeResponse.self, path: try sessionPath(sessionID, "/tree"), query: query)
        } catch let error as HostClientError where error == .capabilityMissing {
            return nil
        } catch {
            throw Self.map(error)
        }
    }

    /// C09：下载会话工作区文件（`GET /sessions/:id/file?path=`）。
    /// 返回原始 bytes + 文件名（`x-dsh-link-filename`）；下载失败/能力缺失抛错，
    /// App 侧显示"未下载"或对应说明，不留下无效链接。
    func downloadFile(sessionID: String, path: String) async throws -> DownloadedWorkspaceFile {
        let http = try await connect()
        let response = try await http.getRaw(path: try sessionPath(sessionID, "/file"), query: ["path": path])
        guard let bytes = acceptedDownload(bytes: response.data, sha256: response.headers["x-dsh-link-sha256"]) else {
            throw HostClientError.decoding("File checksum mismatch")
        }
        return DownloadedWorkspaceFile(
            data: bytes, filename: response.headers["x-dsh-link-filename"],
            contentType: response.headers["content-type"])
    }

    /// C09：已批准预览（`GET /previews`）。
    func previews() async throws -> [PreviewInfo] {
        let http = try await connect()
        let response = try await http.get(PreviewsResponse.self, path: "/dsh-link/mobile/previews")
        return (response.previews ?? []).filter {
            $0.expiresAt == nil || ($0.expiresAt ?? 0) > Int(Date.now.timeIntervalSince1970 * 1000)
        }
    }

    /// C09：检测到的端口（`GET /preview-detections`）。只读，不能批准。
    func previewDetections() async throws -> [PreviewDetection] {
        let http = try await connect()
        let response = try await http.get(PreviewDetectionsResponse.self, path: "/dsh-link/mobile/preview-detections")
        return response.detections ?? []
    }

    func previewExchange(path: String) async -> PreviewHTTPResult {
        guard path.hasPrefix("/dsh-link/mobile/preview/") else {
            return PreviewHTTPResult(status: 404, body: Data())
        }
        do {
            let http = try await connect()
            let result = try await http.exchange(method: "GET", path: path, body: nil)
            return PreviewHTTPResult(status: result.status, contentType: result.contentType, body: result.data)
        } catch {
            return PreviewHTTPResult(status: 502, body: Data())
        }
    }

    func deleteSchedule(sessionID: String, scheduleID: String) async throws {
        let http = try await connect()
        guard !scheduleID.isEmpty, scheduleID.rangeOfCharacter(from: CharacterSet(charactersIn: "/?#")) == nil else {
            throw ConversationServiceError.failed
        }
        do {
            _ = try await http.delete(path: try sessionPath(sessionID, "/schedules/\(scheduleID)"))
        } catch {
            throw Self.map(error)
        }
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
            continuation?.yield(.statusEvent(name: name, data: value, raw: data))
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

private struct ApprovalBody: Encodable {
    var approvalId: String
    var outcome: String
}

private struct QuestionBody: Encodable {
    var rpcId: String
    var answer: QuestionAnswerBody
}

private struct EmptyJSON: Encodable {}

private struct TitleBody: Encodable {
    var title: String
}

private struct PresetBody: Encodable {
    var preset: String
}

private struct ModelBody: Encodable {
    var provider: String
    var model: String
    var reasoningEffort: String?
}

private struct ForkBodyResponse: Decodable {
    var sessionId: String?
}

private struct GoalRefBody: Encodable {
    var id: String
    var revision: Int
}

private struct GoalEditBody: Encodable {
    var ref: GoalRefBody
    var objective: String
    var maxGoalRounds: Int
}

private struct GoalClearBody: Encodable {
    var ref: GoalRefBody
}
