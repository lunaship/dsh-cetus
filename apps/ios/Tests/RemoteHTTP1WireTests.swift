import DLNet
import Foundation
import Testing

/// G4.1：HTTP/1.1 请求组装与响应解析的纯函数测试。
///
/// 这一层不碰网络，覆盖「URLProtocol 拿到的 URLRequest 怎么变成隧道里的字节」
/// 以及「隧道回来的字节怎么变成 HTTPURLResponse + body」。
struct RemoteHTTP1WireTests {
    // MARK: - 请求组装

    @Test func getRequestUsesOriginFormAndHostHeader() throws {
        var request = URLRequest(url: URL(string: "https://192.168.1.5:18640/dsh-link/state?afterSeq=7")!)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("tok-123", forHTTPHeaderField: "x-dsh-link-token")

        let data = try HTTP1Wire.requestData(for: request)
        let text = String(decoding: data, as: UTF8.self)

        #expect(text.hasPrefix("GET /dsh-link/state?afterSeq=7 HTTP/1.1\r\n"))
        #expect(text.contains("Host: 192.168.1.5:18640\r\n"))
        #expect(text.contains("x-dsh-link-token: tok-123\r\n"))
        #expect(text.contains("Accept-Encoding: identity\r\n"))
        #expect(text.hasSuffix("\r\n\r\n"))
        // GET 没有 body，不该发 Content-Length。
        #expect(!text.contains("Content-Length"))
    }

    @Test func postRequestCarriesContentLengthAndBody() throws {
        let body = Data(#"{"prompt":"hi"}"#.utf8)
        var request = URLRequest(url: URL(string: "https://host.test:18640/dsh-link/pair")!)
        request.httpMethod = "POST"
        request.httpBody = body
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let data = try HTTP1Wire.requestData(for: request)
        let text = String(decoding: data, as: UTF8.self)

        #expect(text.hasPrefix("POST /dsh-link/pair HTTP/1.1\r\n"))
        #expect(text.contains("Content-Length: \(body.count)\r\n"))
        // body 原样附在头之后。
        let headEnd = try #require(text.range(of: "\r\n\r\n"))
        #expect(Data(text[headEnd.upperBound...].utf8) == body)
    }

    @Test func defaultPortsAreOmittedFromHostHeader() throws {
        let secure = URLRequest(url: URL(string: "https://relay.example/ws")!)
        #expect(HTTP1Wire.hostHeader(secure.url!).hasSuffix("relay.example"))
        #expect(!HTTP1Wire.hostHeader(secure.url!).contains(":443"))

        let custom = URLRequest(url: URL(string: "https://relay.example:8443/ws")!)
        #expect(HTTP1Wire.hostHeader(custom.url!) == "relay.example:8443")
    }

    @Test func hopByHopHeadersAreNotForwarded() throws {
        var request = URLRequest(url: URL(string: "https://host.test/api")!)
        request.httpMethod = "GET"
        request.setValue("keep-alive", forHTTPHeaderField: "Connection")
        request.setValue("gzip", forHTTPHeaderField: "Accept-Encoding")
        request.setValue("999", forHTTPHeaderField: "Content-Length")

        let text = String(decoding: try HTTP1Wire.requestData(for: request), as: UTF8.self)
        // 由本实现自己决定连接语义与压缩，不透传对端的逐跳头。
        #expect(!text.contains("Connection: keep-alive"))
        #expect(!text.contains("Accept-Encoding: gzip"))
        #expect(!text.contains("Content-Length: 999"))
        #expect(text.contains("Connection: close\r\n"))
    }

    @Test func requestWithoutURLIsRejected() {
        let request = URLRequest(url: URL(string: "https://host.test")!)
        // 拿掉 url 模拟无法组装的情形。
        var broken = request
        broken.url = nil
        #expect(throws: RemoteTunnelError.self) {
            _ = try HTTP1Wire.requestData(for: broken)
        }
    }

    // MARK: - 响应解析

    @Test func parsesContentLengthResponseSplitAcrossChunks() throws {
        var parser = HTTP1Wire.ResponseParser()
        // Content-Length 必须与 body 实际长度一致：写死 "11" 而 body 只有 7 字节
        // 会让解析器（正确地）继续等剩下 4 字节，测试反而测了个错的场景。
        let expectedBody = #"{"a":1}"#
        let head =
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: \(expectedBody.utf8.count)\r\n\r\n"
        // 故意把头和 body 拆成多段喂进去，模拟隧道分片；
        // 最后一片补齐 Content-Length 的那一刻 push 必须返回 true。
        #expect(try parser.push(Data(head.prefix(20).utf8)) == false)
        #expect(try parser.push(Data(head.dropFirst(20).utf8)) == false)
        #expect(try parser.push(Data(#"{"a":1"#.utf8)) == false)
        #expect(try parser.push(Data("}".utf8)) == true)

        let parsed = parser.finish()
        let response = try #require(parsed)
        #expect(response.status == 200)
        #expect(response.headers["content-type"] == "application/json")
        #expect(String(decoding: response.body, as: UTF8.self) == #"{"a":1}"#)
    }

    @Test func parsesChunkedResponse() throws {
        var parser = HTTP1Wire.ResponseParser()
        let raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
        #expect(try parser.push(Data(raw.utf8)) == true)
        let parsed = parser.finish()
        let response = try #require(parsed)
        #expect(response.status == 200)
        #expect(String(decoding: response.body, as: UTF8.self) == "hello world")
    }

    @Test func parsesResponseWithoutContentLengthAtEOF() throws {
        var parser = HTTP1Wire.ResponseParser()
        // 无 Content-Length、非 chunked：读到 EOF 才算完（SSE 常见形态）。
        #expect(
            try parser.push(Data("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\ndata: 1\n\n".utf8)) == false
        )
        let parsed = parser.finish()
        let response = try #require(parsed)
        #expect(response.status == 200)
        #expect(String(decoding: response.body, as: UTF8.self) == "data: 1\n\n")
    }

    @Test func parsesErrorStatusAndBody() throws {
        var parser = HTTP1Wire.ResponseParser()
        let body = #"{"error":"gone","code":"UNKNOWN_KEY"}"#
        let raw =
            "HTTP/1.1 403 Forbidden\r\nContent-Type: application/json\r\nContent-Length: \(body.utf8.count)\r\n\r\n\(body)"
        #expect(try parser.push(Data(raw.utf8)) == true)
        let parsed = parser.finish()
        let response = try #require(parsed)
        #expect(response.status == 403)
        #expect(String(decoding: response.body, as: UTF8.self) == body)
    }

    @Test func rejectsMalformedStatusLine() {
        var parser = HTTP1Wire.ResponseParser()
        #expect(throws: RemoteTunnelError.self) {
            _ = try parser.push(Data("NOT-HTTP garbage\r\n\r\n".utf8))
        }
    }

    @Test func capsHeaderSize() {
        var parser = HTTP1Wire.ResponseParser()
        // 只发头不发尾：超过上限就报错，不允许无界吃内存。
        let filler = String(repeating: "X", count: HTTP1Wire.ResponseParser.maxHeaderBytes + 16)
        #expect(throws: RemoteTunnelError.self) {
            _ = try parser.push(Data(("HTTP/1.1 200 OK\r\nX-Pad: " + filler).utf8))
        }
    }
}
