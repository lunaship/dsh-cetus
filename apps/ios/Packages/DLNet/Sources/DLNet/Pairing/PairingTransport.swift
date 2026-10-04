import DLSecurity
import Foundation

/// 配对需要原始 HTTP 状态和响应体（SAME_NAME、pending、主机 hint），复用 I3.5 的 session / 钉扎。
struct PairingTransport: Sendable {
    let client: HostClient

    /// requirePin 门禁已经执行。无指纹且明确允许的公网地址沿用 I3.4 的系统 PKI 例外；
    /// 有指纹则用 HostClient 的钉扎 session，私网绝不在此获得 PKI 回退。
    static func makeClient(
        baseURL: URL, token: String, fingerprint: String, requestSeconds: Double, session: URLSession?
    ) -> HostClient {
        let resolvedSession: URLSession?
        if session == nil && CertificateFingerprint.normalize(fingerprint).isEmpty {
            let config = URLSessionConfiguration.default
            config.timeoutIntervalForRequest = requestSeconds
            config.timeoutIntervalForResource = 60
            config.requestCachePolicy = .reloadIgnoringLocalCacheData
            resolvedSession = URLSession(configuration: config)
        } else {
            resolvedSession = session
        }
        return HostClient(
            baseURL: baseURL, token: token, expectedFingerprint: fingerprint,
            timeouts: .init(request: .seconds(requestSeconds)), session: resolvedSession)
    }

    func send(method: String, path: String, body: Data? = nil) async throws -> (status: Int, body: Data) {
        var request = URLRequest(url: HostClient.url(baseURL: client.baseURL, path: path, query: [:]))
        request.httpMethod = method
        request.httpBody = body
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if body != nil { request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        if !client.token.isEmpty { request.setValue(client.token, forHTTPHeaderField: HostClient.tokenHeaderName) }
        let pinBefore = (client.session.delegate as? PinnedSessionDelegate)?.pinFailureCount
        do {
            let (data, response) = try await client.session.data(for: request)
            try Task.checkCancellation()
            guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
            return (http.statusCode, data)
        } catch {
            try Task.checkCancellation()
            let pinAfter = (client.session.delegate as? PinnedSessionDelegate)?.pinFailureCount
            throw HostClient.mapTransportError(error, pinErrorChanged: pinAfter != pinBefore)
        }
    }
}
