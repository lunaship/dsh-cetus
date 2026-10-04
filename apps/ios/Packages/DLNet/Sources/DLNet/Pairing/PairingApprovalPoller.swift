import DLSecurity
import Foundation

/// Android PairApprovalPoller：立即探测，pending / unknown 间隔 2 秒；不依本机 pendingExpiresAt 截止。
public struct PairingApprovalPoller: Sendable {
    public enum Approval: Equatable, Sendable {
        case approved, pending, rejected, unknown
    }
    private let store: HostStore
    private let session: URLSession?
    private let interval: Duration
    private let sleep: @Sendable (Duration) async throws -> Void

    public init(
        store: HostStore, session: URLSession? = nil, interval: Duration = .seconds(2),
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) {
        self.store = store
        self.session = session
        self.interval = interval
        self.sleep = sleep
    }

    /// 调用方用 Task.cancel 停止轮询（例如页面离开），取消本身保留凭据。
    /// 用户点「取消配对」时，先取消 Task，再调用 discardPendingPairing（PLAN I4.1）。
    /// 只有插件通过钉扎直连返回 401 才清本条凭据；网络 / 证书错误继续等。
    public func wait(for host: PairedHost) async throws -> Approval {
        try Task.checkCancellation()
        try PinEvaluation.requirePin(url: host.primaryUrl, fingerprint: host.certFingerprint)
        guard let url = URL(string: host.primaryUrl) else { throw PairingError.invalidAddress }
        let token = await store.token(for: host.hostId) ?? ""
        let client = PairingTransport.makeClient(
            baseURL: url, token: token, fingerprint: host.certFingerprint, requestSeconds: 6, session: session)
        defer { if session == nil { client.session.finishTasksAndInvalidate() } }
        while true {
            try Task.checkCancellation()
            let state: Approval
            do {
                let response = try await PairingTransport(client: client).send(
                    method: "GET", path: "/dsh-link/mobile/sessions")
                state = Self.approval(status: response.status, body: response.body)
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                state = .unknown
            }
            try Task.checkCancellation()
            switch state {
            case .approved: return .approved
            case .rejected:
                try await store.delete(hostId: host.hostId)
                return .rejected
            case .pending, .unknown: try await sleep(interval)
            }
        }
    }

    /// 仅清本机记录；不请求设备吊销接口。调用方只能传入当前等待页的 pending host。
    public func discardPendingPairing(for host: PairedHost) async throws {
        try await store.delete(hostId: host.hostId)
    }

    public static func approval(status: Int, body: Data) -> Approval {
        if (200...299).contains(status) { return .approved }
        if status == 401 { return .rejected }
        if status == 403, (try? JSONDecoder().decode(PendingBody.self, from: body))?.pending == true { return .pending }
        return .unknown
    }

    private struct PendingBody: Decodable { let pending: Bool? }
}
