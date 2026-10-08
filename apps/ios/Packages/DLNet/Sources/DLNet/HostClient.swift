import DLModels
import DLSecurity
import Foundation

/// 主机 HTTP 错误映射（PLAN I3.5）。
///
/// 形状以合同与 fixtures 为准：错误体是 `{ "error": string, "code"?: string, "pending"?: bool }`
/// （409 `session_busy`、409 `SAME_NAME`、403 pending「设备待主机确认」、404 能力缺失等）。
/// **红线：401 只上报，由上层决定是否清理凭据；本类型不删除任何凭据。**
public enum HostClientError: Error, Equatable, Sendable {
    /// 401：设备 token 无效或被吊销。只提示，不删凭据。
    case unauthorized
    /// 403：`pending` 为 true 表示设备待主机确认（合同「403 + pending = 继续」）。
    case forbidden(pending: Bool)
    /// 409 且 `code == "session_busy"`：目标会话被其他写方占用，提示换会话，不得自动重试。
    case sessionBusy
    /// 409 其他 code（如配对的 `SAME_NAME`）。
    case conflict(code: String?)
    /// 404：能力缺失（如 `changes_unsupported` / `changes_unavailable` / 路由不存在）。
    case capabilityMissing
    /// 其余 5xx/4xx。
    case server(status: Int, code: String?)
    /// 传输层失败。
    case transport(URLError)
    /// 服务器证书与配对时不一致（I3.4 钉扎失败）：提示「需要重新配对」，不删凭据。
    case certificateChanged
    /// 响应不是合法 JSON / 不符合期望形状。
    case decoding(String)
}

/// 主机 HTTP 客户端（PLAN I3.5）。
///
/// - 统一加设备 token 头 `x-dsh-link-token`（与插件 `src/auth.js` 一致；
///   `Authorization: Bearer` 只是插件的兼容回退，这里不发）。
/// - 默认 session 用 I3.4 的 `PinnedSessionDelegate` 钉扎证书；测试注入自己的 session。
/// - `get` / `post` 返回 DLModels 的 Decodable 类型；超时可配置。
/// - 证书变更检测：请求前后对比 `PinnedSessionDelegate.pinFailureCount`，
///   期间钉扎失败次数增加即映射 `.certificateChanged`（优先于普通 URLError）。
public struct HostClient: Sendable {
    /// 设备 token 请求头名（插件 `src/auth.js`）。
    public static let tokenHeaderName = "x-dsh-link-token"

    public struct Timeouts: Sendable {
        /// 单请求超时（连接与响应空闲）。Android 局域网口径：连接 8s / 读 12s，这里取读超时。
        public var request: Duration

        public init(request: Duration = .seconds(12)) {
            self.request = request
        }
    }

    public let baseURL: URL
    public let token: String
    public let session: URLSession
    public let timeouts: Timeouts
    /// 实际使用的传输。默认是 `URLSessionHostTransport`（局域网 / Tailscale）；
    /// 远程（DLP/1）注入 `RemoteHostTransport`。上层 API 与错误分类不因它而变。
    public let transport: any HostTransport

    /// - Parameters:
    ///   - baseURL: 主机 API 根地址（如 `https://192.168.1.5:18640`），不带路径。
    ///   - token: 设备 token（来自 `HostStore`）。
    ///   - expectedFingerprint: 配对时记录的叶证书指纹，只在使用默认 session 时生效。
    ///   - session: 注入自定义 session（测试）；默认用 `PinnedSessionDelegate` 构造。
    ///   - transport: 注入传输（远程路径）。传 nil 时按 `session` 走 `URLSession`。
    ///     远程路径下 `baseURL` 只用于拼路径与 Host 展示，实际连接由传输决定。
    public init(
        baseURL: URL,
        token: String,
        expectedFingerprint: String? = nil,
        timeouts: Timeouts = .init(),
        session: URLSession? = nil,
        transport: (any HostTransport)? = nil
    ) {
        self.baseURL = baseURL
        self.token = token
        self.timeouts = timeouts
        if let session {
            self.session = session
        } else {
            let configuration = URLSessionConfiguration.default
            let requestSeconds = Self.seconds(timeouts.request)
            configuration.timeoutIntervalForRequest = requestSeconds
            configuration.timeoutIntervalForResource = max(requestSeconds * 4, 60)
            configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
            self.session = URLSession(
                configuration: configuration,
                delegate: PinnedSessionDelegate(expectedFingerprint: expectedFingerprint),
                delegateQueue: nil
            )
        }
        self.transport = transport ?? URLSessionHostTransport(session: self.session)
    }

    /// GET 并解码为 DLModels 类型。
    public func get<T: Decodable>(_ type: T.Type, path: String, query: [String: String] = [:]) async throws -> T {
        try await perform(
            Self.makeRequest(
                method: "GET", url: Self.url(baseURL: baseURL, path: path, query: query), body: nil, token: token))
    }

    /// POST JSON 并解码为 DLModels 类型。
    public func post<T: Decodable>(
        _ type: T.Type,
        path: String,
        json body: some Encodable,
        query: [String: String] = [:]
    ) async throws -> T {
        let data = try JSONEncoder().encode(body)
        return try await perform(
            Self.makeRequest(
                method: "POST", url: Self.url(baseURL: baseURL, path: path, query: query), body: data, token: token))
    }

    /// 把状态码和正文都交回调用方。预览代理要转发 4xx，不能在这里抛掉。
    public func exchange(method: String, path: String, body: Data?) async throws -> (
        status: Int, data: Data, contentType: String
    ) {
        let request = Self.makeRequest(
            method: method, url: Self.url(baseURL: baseURL, path: path, query: [:]), body: body, token: token)
        let pinBefore = Self.pinFailureCount(transport)
        do {
            let response = try await transport.send(request)
            let type = Self.headerValue(response.headers, "content-type") ?? "application/octet-stream"
            return (response.statusCode, response.body, type)
        } catch let error as HostClientError {
            throw error
        } catch {
            throw Self.mapTransportError(error, pinErrorChanged: Self.pinFailed(transport, before: pinBefore))
        }
    }

    public struct RawResponse: Sendable {
        public var data: Data
        /// Lowercase keys allow callers to read headers independent of server capitalization.
        public var headers: [String: String]
    }

    /// Download bounded bytes using the same pinned session and error mapping as JSON requests.
    public func getRaw(
        path: String, query: [String: String] = [:], maxBytes: Int = 8 * 1024 * 1024
    ) async throws -> RawResponse {
        let pinBefore = (session.delegate as? PinnedSessionDelegate)?.pinFailureCount
        do {
            let request = Self.makeRequest(
                method: "GET", url: Self.url(baseURL: baseURL, path: path, query: query), body: nil, token: token)
            let (bytes, response) = try await session.bytes(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw HostClientError.transport(URLError(.badServerResponse))
            }
            guard maxBytes >= 0, response.expectedContentLength <= Int64(maxBytes) else {
                throw HostClientError.decoding("File exceeds download limit")
            }
            var data = Data()
            for try await byte in bytes {
                guard data.count < maxBytes else {
                    throw HostClientError.decoding("File exceeds download limit")
                }
                data.append(byte)
            }
            guard (200..<300).contains(http.statusCode) else {
                throw Self.mapStatus(http.statusCode, body: data)
            }
            var headers: [String: String] = [:]
            for (key, value) in http.allHeaderFields {
                if let name = key as? String { headers[name.lowercased()] = String(describing: value) }
            }
            return RawResponse(data: data, headers: headers)
        } catch let error as HostClientError {
            throw error
        } catch {
            let pinAfter = (session.delegate as? PinnedSessionDelegate)?.pinFailureCount
            throw Self.mapTransportError(error, pinErrorChanged: pinAfter != pinBefore)
        }
    }

    /// DELETE。2xx 的正文原样返回。
    public func delete(path: String, query: [String: String] = [:]) async throws -> Data {
        try await send(
            Self.makeRequest(
                method: "DELETE", url: Self.url(baseURL: baseURL, path: path, query: query), body: nil, token: token))
    }

    /// POST 已经编码好的 JSON。正文形状由调用方的纯函数决定。
    public func postJSONData(path: String, body: Data, query: [String: String] = [:]) async throws -> Data {
        try await send(
            Self.makeRequest(
                method: "POST", url: Self.url(baseURL: baseURL, path: path, query: query), body: body, token: token))
    }

    /// POST JSON。2xx 的正文原样返回，调用方自己解码。空正文也算成功。
    public func postJSON(path: String, json body: some Encodable, query: [String: String] = [:]) async throws -> Data {
        let data = try JSONEncoder().encode(body)
        return try await send(
            Self.makeRequest(
                method: "POST", url: Self.url(baseURL: baseURL, path: path, query: query), body: data, token: token))
    }

    // MARK: - 错误映射（纯函数，供测试与后续 DLRemote 复用）

    /// 大小写不敏感地取一个响应头。
    ///
    /// `URLSession` 与隧道两条路径交回的键大小写不同（HTTP/1.1 头名不区分大小写），
    /// 这里统一成小写比较，避免调用方各写一遍。
    static func headerValue(_ headers: [String: String], _ name: String) -> String? {
        let wanted = name.lowercased()
        for (key, value) in headers where key.lowercased() == wanted { return value }
        return nil
    }

    /// 请求期间是否发生了证书钉扎失败。
    ///
    /// `pinFailureCount` 是**累计**计数（只增不减），所以必须传请求前读到的值做前后比较 ——
    /// 单看当前值会把"以前失败过一次"误判成"这次失败了"。
    ///
    /// 远程路径没有 `PinnedSessionDelegate`：它的钉扎失败已在 `RemoteHostTransport.map`
    /// 里直接映射成 `.certificateChanged`，因此这里返回 false，不重复判定。
    static func pinFailed(_ transport: any HostTransport, before: Int) -> Bool {
        (transport as? URLSessionHostTransport)?.pinFailureCount ?? 0 > before
    }

    /// 读当前钉扎失败计数（请求前调用，交给 `pinFailed` 做前后比较）。
    static func pinFailureCount(_ transport: any HostTransport) -> Int {
        (transport as? URLSessionHostTransport)?.pinFailureCount ?? 0
    }

    /// 状态码 + 错误体 → `HostClientError`。
    public static func mapStatus(_ status: Int, body: Data) -> HostClientError {
        let parsed = try? JSONDecoder().decode(ErrorBody.self, from: body)
        switch status {
        case 401:
            return .unauthorized
        case 403:
            return .forbidden(pending: parsed?.pending ?? false)
        case 409:
            if parsed?.code == "session_busy" {
                return .sessionBusy
            }
            return .conflict(code: parsed?.code)
        case 404:
            return .capabilityMissing
        default:
            return .server(status: status, code: parsed?.code)
        }
    }

    /// 传输失败 → `HostClientError`。`pinErrorChanged` 表示请求期间证书钉扎刚记录了失败
    /// （I3.4 委托的 `pinFailureCount` 在请求期间增加），此时优先映射 `.certificateChanged`。
    public static func mapTransportError(_ error: any Error, pinErrorChanged: Bool) -> HostClientError {
        if pinErrorChanged {
            return .certificateChanged
        }
        if let urlError = error as? URLError {
            return .transport(urlError)
        }
        return .transport(URLError(.unknown))
    }

    // MARK: - 内部

    private struct ErrorBody: Decodable {
        var error: String?
        var code: String?
        var pending: Bool?
    }

    private func perform<T: Decodable>(_ request: URLRequest) async throws -> T {
        let data = try await send(request)
        do {
            return try JSONDecoder().decodePreservingRawJSON(T.self, from: data)
        } catch {
            throw HostClientError.decoding(String(describing: error))
        }
    }

    private func send(_ request: URLRequest) async throws -> Data {
        let pinBefore = (session.delegate as? PinnedSessionDelegate)?.pinFailureCount
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw HostClientError.transport(URLError(.badServerResponse))
            }
            guard (200..<300).contains(http.statusCode) else {
                throw Self.mapStatus(http.statusCode, body: data)
            }
            return data
        } catch let error as HostClientError {
            throw error
        } catch {
            let pinAfter = (session.delegate as? PinnedSessionDelegate)?.pinFailureCount
            throw Self.mapTransportError(error, pinErrorChanged: pinAfter != pinBefore)
        }
    }

    private static func makeRequest(method: String, url: URL, body: Data?, token: String) -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        if body != nil {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(token, forHTTPHeaderField: tokenHeaderName)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        return request
    }

    /// 拼 URL：`baseURL` + `path`（以 / 开头）+ 查询参数。查询值手工百分号编码，
    /// `+` 一并转义（Android 同款处理：`MobileApi.kt` 把 `+` 替换为 `%20`，
    /// 因为 Node `URLSearchParams` 会把查询串里的裸 `+` 当空格）。
    public static func url(baseURL: URL, path: String, query: [String: String]) -> URL {
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)
        components?.path = path
        if !query.isEmpty {
            let items =
                query
                .sorted { $0.key < $1.key }
                .map { key, value in
                    URLQueryItem(name: encodeQueryComponent(key), value: encodeQueryComponent(value))
                }
            components?.percentEncodedQueryItems = items
        }
        return components?.url ?? baseURL
    }

    private static let queryAllowed = CharacterSet.urlQueryAllowed.subtracting(CharacterSet(charactersIn: "+&="))

    private static func encodeQueryComponent(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: queryAllowed) ?? value
    }

    private static func seconds(_ duration: Duration) -> TimeInterval {
        let (wholeSeconds, attoseconds) = duration.components
        return Double(wholeSeconds) + Double(attoseconds) * 1e-18
    }
}
