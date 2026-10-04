import Foundation

/// 一条已打开的 SSE 连接（I3.5）。
///
/// `statusCode` 来自底层响应；非 2xx 时 `lines` 里通常没有 SSE 帧（错误体不是事件流），
/// 消费方应先看状态码再决定读行。`cancel()` 主动断开底层连接；
/// 不读行时也应 cancel（或直接丢弃——行流终止时会联动取消底层）。
public struct SSEConnection: Sendable {
    public let statusCode: Int?
    public let lines: AsyncThrowingStream<String, any Error>
    private let onCancel: @Sendable () -> Void

    public init(
        statusCode: Int?,
        lines: AsyncThrowingStream<String, any Error>,
        onCancel: @escaping @Sendable () -> Void = {}
    ) {
        self.statusCode = statusCode
        self.lines = lines
        self.onCancel = onCancel
    }

    public func cancel() {
        onCancel()
    }
}

/// SSE 传输抽象（I3.5）：打开一条连接并拿到行流。
/// 生产实现是 `URLSessionSSETransport`；测试注入脚本化假传输，不开真实端口。
public protocol SSETransport: Sendable {
    func open(_ request: URLRequest) async throws -> SSEConnection
}

/// 把字节流切成 SSE 行，**保留空行**（I3.5）。
///
/// WHATWG SSE：CRLF、LF、单独的 CR 都是行尾。不能直接用 `URLSession.AsyncBytes.lines`：
/// Foundation 的 `AsyncLineSequence` 会吞掉空行，而 SSE 正是靠空行分帧，用它解析器永远凑不齐一帧。
public struct SSELineSplitter: Sendable {
    private var buffer: [UInt8] = []
    private var lastWasCR = false

    public init() {}

    /// 喂一个字节；遇到行尾时返回这一行（不含行尾，可能是空串）。
    public mutating func push(_ byte: UInt8) -> String? {
        switch byte {
        case 0x0A:
            if lastWasCR {
                // CRLF 的 LF：行已在 CR 处交出。
                lastWasCR = false
                return nil
            }
            return flush()
        case 0x0D:
            lastWasCR = true
            return flush()
        default:
            lastWasCR = false
            buffer.append(byte)
            return nil
        }
    }

    /// 流结束时剩下的、没有行尾的半行。
    public mutating func finish() -> String? {
        buffer.isEmpty ? nil : flush()
    }

    private mutating func flush() -> String {
        defer { buffer.removeAll(keepingCapacity: true) }
        return String(decoding: buffer, as: UTF8.self)
    }
}

/// `URLSession.bytes(for:)` + `SSELineSplitter` 逐行切分的默认传输（PLAN I3.5）。
///
/// 默认构造的 session 关掉了请求 / 资源超时：SSE 是长连接，断线由客户端看门狗判定
/// （会话流 15 秒一条 `: keepalive`，主机事件流 25 秒一条 `heartbeat`）。
public struct URLSessionSSETransport: SSETransport {
    public let session: URLSession

    public init(session: URLSession? = nil) {
        if let session {
            self.session = session
        } else {
            let configuration = URLSessionConfiguration.default
            configuration.timeoutIntervalForRequest = 0
            configuration.timeoutIntervalForResource = 0
            configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
            self.session = URLSession(configuration: configuration)
        }
    }

    public func open(_ request: URLRequest) async throws -> SSEConnection {
        let (bytes, response) = try await session.bytes(for: request)
        let statusCode = (response as? HTTPURLResponse)?.statusCode
        let (stream, continuation) = AsyncThrowingStream<String, any Error>.makeStream()
        let task = Task {
            do {
                var splitter = SSELineSplitter()
                for try await byte in bytes {
                    if let line = splitter.push(byte) {
                        continuation.yield(line)
                    }
                }
                if let rest = splitter.finish() {
                    continuation.yield(rest)
                }
                continuation.finish()
            } catch {
                continuation.finish(throwing: error)
            }
        }
        continuation.onTermination = { _ in task.cancel() }
        return SSEConnection(statusCode: statusCode, lines: stream, onCancel: { task.cancel() })
    }
}
