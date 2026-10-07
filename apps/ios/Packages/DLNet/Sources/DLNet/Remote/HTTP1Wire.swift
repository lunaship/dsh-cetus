import Foundation

/// HTTP/1.1 请求序列化与响应解析（G4.1）。
///
/// `URLProtocol` 拿到的是已经组装好的 `URLRequest`，隧道那头是插件的 Node HTTP 服务，
/// 因此这里只覆盖本 App 实际用到的形态：GET / POST / DELETE + JSON + SSE。
/// 范围有意收窄——不实现 chunked 请求体、不实现 `Expect: 100-continue`、不做重定向。
public enum HTTP1Wire {
    /// 行尾固定 CRLF（RFC 9112）。
    static let crlf = "\r\n"

    /// 把 `URLRequest` 组装成 origin-form 的 HTTP/1.1 请求字节。
    ///
    /// - origin-form 只带 path + query（隧道内没有代理语义，不需要 absolute-form）。
    /// - `Host` 用 url 的 host + 非默认端口。
    /// - `Content-Length` 由 body 决定；无 body 的 GET / DELETE 不发 `Content-Length`。
    /// - 逐跳头（`Connection`、`Transfer-Encoding` 等）不转发：隧道是一条裸字节流，
    ///   由本实现自己决定连接生命周期。
    public static func requestData(for request: URLRequest) throws -> Data {
        guard let url = request.url else { throw RemoteTunnelError.protocolViolation }
        let method = (request.httpMethod ?? "GET").uppercased()
        let body = request.httpBody ?? request.httpBodyStream.flatMap(readAll)

        var head = method + " " + originForm(url) + " HTTP/1.1" + crlf
        head += "Host: " + hostHeader(url) + crlf

        for (name, value) in normalizedHeaders(request.allHTTPHeaderFields ?? [:]) {
            switch name.lowercased() {
            case "host", "connection", "transfer-encoding", "content-length", "accept-encoding":
                continue
            default:
                head += name + ": " + value + crlf
            }
        }
        if let body, !body.isEmpty {
            head += "Content-Length: " + String(body.count) + crlf
        }

        // 不接受压缩：响应要按字节交给调用方解析，且 URLProtocol 不做透明解压。
        head += "Accept-Encoding: identity" + crlf
        // 隧道每条流一条连接，等价于 close 语义；不带 keep-alive。
        head += "Connection: close" + crlf
        head += crlf

        var data = Data(head.utf8)
        if let body, !body.isEmpty { data.append(body) }
        return data
    }

    /// 一次增量解析的状态机。喂入隧道来的任意分片，直到响应完整或出错。
    public struct ResponseParser {
        /// 响应头最大字节数，防止对端只发头不发尾把内存吃光。
        public static let maxHeaderBytes = 64 * 1024

        private var buffer = Data()
        private var headerEnd: Int?
        private var contentLength: Int?
        private var chunked = false
        private var statusCode: Int?
        private var headers: [String: String] = [:]
        private var body = Data()
        private var remainingChunk = 0
        private var chunkedDone = false

        public init() {}

        /// 喂一段字节。返回 true 表示响应已经完整。
        public mutating func push(_ bytes: Data) throws -> Bool {
            buffer.append(bytes)
            if headerEnd == nil {
                try parseHead()
            }
            guard let headerEnd else { return false }
            if chunked {
                try parseChunkedBody(from: headerEnd)
            } else if let contentLength {
                let available = buffer.count - headerEnd
                if available >= contentLength {
                    body = buffer.subdata(in: headerEnd..<(headerEnd + contentLength))
                    return true
                }
                return false
            } else {
                // 没有 Content-Length、也不是 chunked：读到 EOF 才算完（由 finish() 收尾）。
                body = buffer.subdata(in: headerEnd..<buffer.count)
                return false
            }
            return chunkedDone
        }

        /// 对端关流时调用：把「无 Content-Length 的响应」按读到 EOF 收尾。
        public mutating func finish() -> (status: Int, headers: [String: String], body: Data)? {
            guard let statusCode else { return nil }
            if headerEnd != nil, contentLength == nil, !chunked {
                body = buffer.subdata(in: (headerEnd ?? 0)..<buffer.count)
            }
            return (statusCode, headers, body)
        }

        private mutating func parseHead() throws {
            let terminator = Data("\r\n\r\n".utf8)
            guard let range = buffer.range(of: terminator) else {
                if buffer.count > Self.maxHeaderBytes { throw RemoteTunnelError.protocolViolation }
                return
            }
            let headData = buffer.subdata(in: 0..<range.lowerBound)
            guard let headText = String(data: headData, encoding: .utf8) else {
                throw RemoteTunnelError.protocolViolation
            }
            var lines = headText.components(separatedBy: crlf)
            guard !lines.isEmpty else { throw RemoteTunnelError.protocolViolation }
            let statusLine = lines.removeFirst()
            let parts = statusLine.split(separator: " ", maxSplits: 2, omittingEmptySubsequences: false)
            guard parts.count >= 2, parts[0].hasPrefix("HTTP/1."), let code = Int(parts[1]) else {
                throw RemoteTunnelError.protocolViolation
            }
            statusCode = code
            for line in lines where !line.isEmpty {
                guard let colon = line.firstIndex(of: ":") else { continue }
                let name = String(line[line.startIndex..<colon]).trimmingCharacters(in: .whitespaces).lowercased()
                let value = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
                // 同名头重复时以最后一个为准（本 App 不依赖重复头语义）。
                headers[name] = value
            }
            headerEnd = range.upperBound
            if let transfer = headers["transfer-encoding"], transfer.lowercased().contains("chunked") {
                chunked = true
            } else if let length = headers["content-length"], let value = Int(length) {
                contentLength = value
            }
            if statusCode == 204 || statusCode == 304 {
                contentLength = 0
            }
        }

        private mutating func parseChunkedBody(from start: Int) throws {
            var cursor = start
            while true {
                if remainingChunk == 0 {
                    guard let lineEnd = findCRLF(from: cursor) else { return }
                    let sizeText = String(decoding: buffer[cursor..<lineEnd], as: UTF8.self)
                    let sizePart = sizeText.split(separator: ";").first.map(String.init) ?? sizeText
                    guard let size = Int(sizePart.trimmingCharacters(in: .whitespaces), radix: 16) else {
                        throw RemoteTunnelError.protocolViolation
                    }
                    cursor = lineEnd + 2
                    if size == 0 {
                        chunkedDone = true
                        return
                    }
                    remainingChunk = size
                }
                let available = buffer.count - cursor
                if available < remainingChunk + 2 { return }
                body.append(buffer.subdata(in: cursor..<(cursor + remainingChunk)))
                cursor += remainingChunk + 2
                remainingChunk = 0
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

    // MARK: - 工具

    /// origin-form：path + query。
    public static func originForm(_ url: URL) -> String {
        var target = url.path.isEmpty ? "/" : url.path
        if let query = url.query, !query.isEmpty { target += "?" + query }
        return target
    }

    /// `Host` 头：host + 非默认端口。
    public static func hostHeader(_ url: URL) -> String {
        let host = url.host ?? ""
        let port = url.port ?? (url.scheme?.lowercased() == "https" ? 443 : 80)
        let isDefault =
            (url.scheme?.lowercased() == "https" && port == 443) || (url.scheme?.lowercased() == "http" && port == 80)
        return isDefault ? host : host + ":" + String(port)
    }

    /// 头名去重（大小写不敏感），保留首个写法。
    static func normalizedHeaders(_ headers: [String: String]) -> [(String, String)] {
        var seen = Set<String>()
        var out: [(String, String)] = []
        for (name, value) in headers.sorted(by: { $0.key < $1.key }) {
            let lower = name.lowercased()
            if seen.insert(lower).inserted { out.append((name, value)) }
        }
        return out
    }

    private static func readAll(_ stream: InputStream) -> Data? {
        stream.open()
        defer { stream.close() }
        var data = Data()
        let size = 16 * 1024
        var buffer = [UInt8](repeating: 0, count: size)
        while stream.hasBytesAvailable {
            let read = stream.read(&buffer, maxLength: size)
            if read <= 0 { break }
            data.append(buffer, count: read)
        }
        return data.isEmpty ? nil : data
    }
}
