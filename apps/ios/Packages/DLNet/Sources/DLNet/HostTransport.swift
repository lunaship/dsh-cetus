import Foundation

/// `HostClient` 底下的传输抽象。
///
/// ## 为什么需要它
///
/// 两条路做的是**同一件事**（带 token 向插件发 HTTP、读响应、开 SSE），但底座完全不同：
///
/// - **局域网 / Tailscale**：`URLSession` + `PinnedSessionDelegate`。系统 TLS 栈直连主机地址，
///   钉扎由 `didReceive challenge` 回调完成。
/// - **远程（DLP/1）**：`InnerTLSChannel`（`NWConnection` + `NWProtocolTLS` + `installPin`）
///   之上直接写 HTTP/1.1 字节。**不能用 URLSession** —— 见 `docs/cetus/C11-REMOTE.md`：
///   实测 URLSession 通过回环桥时会以 `NSURLErrorDomain -1202` 拒绝插件的自签证书，
///   因为它无法把自己的 TLS 校验替换成对插件证书的钉扎。
///
/// 有了这层抽象，**上层只有一套 API**（`HostClient` 的 `get`/`post`/`exchange`/`getRaw`/SSE），
/// 错误映射（`HostClientError`）也保持一致；4 个调用点不需要知道自己走的是哪条路。
///
/// ## 契约
///
/// 实现必须：
/// - 只在 `URLRequest` 已带好 token 头与 URL 的前提下工作（`HostClient` 负责组装）；
/// - 把**传输层**失败映射成 `HostClientError`（尤其 `.certificateChanged`），
///   不要抛出裸 `URLError` 让上层无法分类；
/// - 对 `send` 返回值给出真实状态码与完整 body（`HostClient` 负责解码与业务错误分类）。
public protocol HostTransport: Sendable {
    /// 发一次请求并拿到完整响应体。用于 `get`/`post`/`exchange`/`getRaw`。
    func send(_ request: URLRequest) async throws -> TransportResponse

    /// 发一次请求并**流式**读取响应体（SSE 用；不把 body 攒进内存）。
    ///
    /// - Returns: 状态码 + 行流。调用方负责取消。
    func stream(_ request: URLRequest) async throws -> TransportStream
}

/// 一次性请求的响应。
public struct TransportResponse: Sendable {
    public var statusCode: Int
    public var body: Data
    /// 原始响应头（键大小写不敏感由调用方决定；这里保持服务端原样）。
    public var headers: [String: String]

    public init(statusCode: Int, body: Data, headers: [String: String] = [:]) {
        self.statusCode = statusCode
        self.body = body
        self.headers = headers
    }
}

/// 流式响应的句柄。
///
/// `lines` 产出的是**已按 SSE 规则切好的行**（保留空行 —— SSE 靠空行分帧）。
/// 这与 `SSELineSplitter` 的语义一致，`SSEClient` 不需要知道底层是 URLSession 还是隧道。
public struct TransportStream: Sendable {
    public var statusCode: Int?
    public var lines: AsyncThrowingStream<String, any Error>
    /// 取消并释放底层连接（幂等）。
    public var onCancel: @Sendable () -> Void

    public init(
        statusCode: Int?,
        lines: AsyncThrowingStream<String, any Error>,
        onCancel: @escaping @Sendable () -> Void
    ) {
        self.statusCode = statusCode
        self.lines = lines
        self.onCancel = onCancel
    }
}

/// 默认实现：`URLSession`（局域网 / Tailscale）。
///
/// 行为与引入本抽象之前**完全一致**：同样的 session、同样的钉扎回调、
/// 同样的 `pinFailureCount` 前后对比。
///
/// `pinFailureCount` 的读法放在这里而不是 `HostClient`：远程实现没有 `PinnedSessionDelegate`，
/// 它用 `InnerTLSChannel` 的拒绝标志表达同一件事。这样 `HostClient` 不必按实现分支。
public struct URLSessionHostTransport: HostTransport {
    public let session: URLSession

    public init(session: URLSession) {
        self.session = session
    }

    /// 本次请求是否发生了钉扎失败（远程实现返回 false 或它自己的等价信号）。
    public var pinFailureCount: Int {
        (session.delegate as? PinnedSessionDelegate)?.pinFailureCount ?? 0
    }

    public func send(_ request: URLRequest) async throws -> TransportResponse {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw HostClientError.transport(URLError(.badServerResponse))
        }
        var headers: [String: String] = [:]
        for (key, value) in http.allHeaderFields {
            if let key = key as? String, let value = value as? String { headers[key] = value }
        }
        return TransportResponse(statusCode: http.statusCode, body: data, headers: headers)
    }

    public func stream(_ request: URLRequest) async throws -> TransportStream {
        // URLSession 的 SSE 走 `bytes(for:)`：逐字节、不攒 body。
        let (bytes, response) = try await session.bytes(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode
        let (stream, continuation) = AsyncThrowingStream<String, any Error>.makeStream()
        let splitter = SSELineSplitterBox()
        let task = Task {
            do {
                for try await byte in bytes {
                    if let line = splitter.push(byte) { continuation.yield(line) }
                }
                continuation.finish()
            } catch {
                continuation.finish(throwing: error)
            }
        }
        continuation.onTermination = { _ in task.cancel() }
        return TransportStream(statusCode: status, lines: stream, onCancel: { task.cancel() })
    }
}

/// `SSELineSplitter` 是值类型；流式循环里需要在 `@Sendable` 闭包中持有可变副本。
/// 用锁保护，避免把可变状态捕获进并发闭包。
private final class SSELineSplitterBox: @unchecked Sendable {
    private let lock = NSLock()
    private var splitter = SSELineSplitter()

    func push(_ byte: UInt8) -> String? {
        lock.lock()
        defer { lock.unlock() }
        return splitter.push(byte)
    }
}
