import Foundation

/// 面向**长连接流**（SSE）的 HTTP/1.1 响应解析器。
///
/// 与 `HTTP1Wire.ResponseParser` 的区别是根本性的，不能混用：
/// - `ResponseParser` 是**一次性**的：攒满 Content-Length / 读完 chunked 才算完，
///   body 在内存里累积——对 SSE 这种「可能几小时不断推事件」的响应会无限吃内存，
///   而且调用方拿不到「增量」。
/// - 本类型是**流式**的：把响应头解析出来后立刻可用，之后每次 `push` 只交出
///   **新增的 body 字节**，不在内部保留 body。
///
/// 只支持本 App 实际遇到的形态：`Content-Length`（一次性事件体）、
/// `chunked`（Node 对无长度响应常用）、以及无长度（读到连接结束）。
public struct HTTP1StreamParser: Sendable {
    /// 响应头最大字节数，防止对端只发头不发尾把内存吃光。
    public static let maxHeaderBytes = 64 * 1024

    /// 响应头就绪后的结果。
    public struct Head: Sendable, Equatable {
        public var status: Int
        public var headers: [String: String]
    }

    /// 本次 `push` 的产出。
    public enum Output: Sendable, Equatable {
        /// 响应头尚未收全，本次没有产出。
        case pending
        /// 响应头刚就绪（只产出一次）。
        case head(Head)
        /// 头已就绪，本次交出这段新增 body 字节（可能为空表示只是消费掉了帧）。
        case body(Data)
    }

    private enum BodyMode {
        case unknown
        case contentLength(remaining: Int)
        case chunked
        case untilEOF
    }

    private var buffer = Data()
    private var head: Head?
    private var mode: BodyMode = .unknown
    private var remainingChunk = 0
    /// chunked 里「上一个块的数据已交出，正在等它后面的 CRLF」。
    private var awaitingChunkCRLF = false
    private var finished = false
    /// 头与部分 body 同批到达时，body 暂存到这里等下一次 push 交出
    /// （一次 push 只产出一种 Output，避免调用方漏处理）。
    private var pending = Data()

    public init() {}

    /// 响应头（未就绪时为 nil）。
    public var resolvedHead: Head? { head }
    /// 是否已经读到响应结束（chunked 的 0 块 / Content-Length 读满）。
    public var isFinished: Bool { finished }

    /// 喂一段字节。返回本次能确定地交出的内容。
    public mutating func push(_ bytes: Data) throws -> Output {
        if head == nil {
            buffer.append(bytes)
            try parseHeadIfAvailable()
            guard let parsed = head else { return .pending }
            // 头刚就绪：先把头交给调用方，body 会在后续 push / 同一次里继续处理。
            let leftover = try drainBody()
            if !leftover.isEmpty {
                // 头与部分 body 在同一批到达：先出头，body 留给下一次 push 消费。
                // 用 pendingBody 暂存，避免一次 push 产出两种结果。
                pending = leftover
            }
            return .head(parsed)
        }
        buffer.append(bytes)
        let out = try drainBody()
        return .body(out)
    }

    /// 把当前 buffer 里可确定的 body 字节交出来。
    ///
    /// 头刚解析完时若同批还带了 body，会先暂存到 `pending`，
    /// 由调用方的下一次 `push` 吐出。
    private mutating func drainBody() throws -> Data {
        var out = Data()
        if !pending.isEmpty {
            out.append(pending)
            pending.removeAll(keepingCapacity: true)
        }
        guard head != nil else { return out }
        switch mode {
        case .unknown:
            return out
        case .contentLength(let remaining):
            var left = remaining
            let available = buffer.count
            let take = min(left, available)
            if take > 0 {
                out.append(buffer.prefix(take))
                buffer.removeFirst(take)
                left -= take
            }
            mode = .contentLength(remaining: left)
            if left == 0 { finished = true }
            return out
        case .chunked:
            try drainChunked(into: &out)
            return out
        case .untilEOF:
            out.append(buffer)
            buffer.removeAll(keepingCapacity: true)
            return out
        }
    }

    private mutating func parseHeadIfAvailable() throws {
        let terminator = Data("\r\n\r\n".utf8)
        guard let range = buffer.range(of: terminator) else {
            if buffer.count > Self.maxHeaderBytes {
                throw RemoteTunnelError.protocolViolation
            }
            return
        }
        let headData = buffer.subdata(in: 0..<range.lowerBound)
        buffer.removeFirst(range.upperBound)
        guard let text = String(data: headData, encoding: .utf8) else {
            throw RemoteTunnelError.protocolViolation
        }
        var lines = text.components(separatedBy: "\r\n")
        guard !lines.isEmpty else { throw RemoteTunnelError.protocolViolation }
        let statusLine = lines.removeFirst()
        let parts = statusLine.split(separator: " ", maxSplits: 2, omittingEmptySubsequences: false)
        guard parts.count >= 2, parts[0].hasPrefix("HTTP/1."), let code = Int(parts[1]) else {
            throw RemoteTunnelError.protocolViolation
        }
        var headers: [String: String] = [:]
        for line in lines where !line.isEmpty {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let name = String(line[line.startIndex..<colon]).trimmingCharacters(in: .whitespaces).lowercased()
            let value = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
            headers[name] = value
        }
        head = Head(status: code, headers: headers)
        if let transfer = headers["transfer-encoding"], transfer.lowercased().contains("chunked") {
            mode = .chunked
        } else if let length = headers["content-length"], let value = Int(length) {
            mode = .contentLength(remaining: value)
        } else {
            // 无长度、非 chunked：SSE 常见形态，读到连接结束为止。
            mode = .untilEOF
        }
        if code == 204 || code == 304 { mode = .contentLength(remaining: 0) }
    }

    private mutating func drainChunked(into out: inout Data) throws {
        while true {
            if awaitingChunkCRLF {
                guard buffer.count >= 2 else { return }
                buffer.removeFirst(2)
                awaitingChunkCRLF = false
                continue
            }
            if remainingChunk == 0 {
                guard let lineEnd = findCRLF(from: 0) else { return }
                let sizeText = String(decoding: buffer.prefix(lineEnd), as: UTF8.self)
                let sizePart = sizeText.split(separator: ";").first.map(String.init) ?? sizeText
                guard let size = Int(sizePart.trimmingCharacters(in: .whitespaces), radix: 16) else {
                    throw RemoteTunnelError.protocolViolation
                }
                buffer.removeFirst(lineEnd + 2)
                if size == 0 {
                    finished = true
                    return
                }
                remainingChunk = size
            }
            guard buffer.count >= remainingChunk else { return }
            out.append(buffer.prefix(remainingChunk))
            buffer.removeFirst(remainingChunk)
            remainingChunk = 0
            awaitingChunkCRLF = true
        }
    }

    private func findCRLF(from start: Int) -> Int? {
        guard buffer.count >= start + 2 else { return nil }
        var index = start
        while index + 1 < buffer.count {
            if buffer[index] == 0x0D, buffer[index + 1] == 0x0A { return index }
            index += 1
        }
        return nil
    }
}
