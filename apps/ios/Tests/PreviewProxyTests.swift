import DLCore
import DLNet
import Foundation
import Network
import Testing

private struct PreviewTestServer: Sendable {
    var listener: NWListener
    var port: NWEndpoint.Port

    static func start() async throws -> PreviewTestServer {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: .any)
        let listener = try NWListener(using: parameters)
        listener.newConnectionHandler = { connection in
            connection.start(queue: .global())
            Task {
                defer { connection.cancel() }
                do {
                    var head = Data()
                    while parsePreviewHead(head) == nil { head.append(try await receive(connection)) }
                    let request = parsePreviewHead(head)!
                    let key = request.webSocketKey!
                    let response =
                        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: \(webSocketAccept(key))\r\n\r\n"
                    try await send(connection, Data(response.utf8) + serverTextFrame("from separate server"))
                    var frameBytes = Data()
                    while readClientFrame(frameBytes) == nil { frameBytes.append(try await receive(connection)) }
                    let frame = readClientFrame(frameBytes)!.frame
                    #expect(frame.opcode == 2)
                    #expect(frame.payload == Data([1, 2, 255]))
                    try await send(connection, Data([0x82, 3, 9, 8, 7]))
                    _ = try await receive(connection)
                } catch {}
            }
        }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready: continuation.resume()
                case .failed(let error): continuation.resume(throwing: error)
                default: break
                }
            }
            listener.start(queue: .global())
        }
        return PreviewTestServer(listener: listener, port: listener.port!)
    }

    static func connect(_ connection: NWConnection) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready: continuation.resume()
                case .failed(let error): continuation.resume(throwing: error)
                default: break
                }
            }
            connection.start(queue: .global())
        }
    }

    static func receive(_ connection: NWConnection) async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { data, _, _, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let data, !data.isEmpty {
                    continuation.resume(returning: data)
                } else {
                    continuation.resume(throwing: URLError(.networkConnectionLost))
                }
            }
        }
    }

    static func send(_ connection: NWConnection, _ data: Data) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(
                content: data,
                completion: .contentProcessed { error in
                    if let error { continuation.resume(throwing: error) } else { continuation.resume() }
                })
        }
    }
}

@Suite struct PreviewProxyTests {
    @Test func loopbackRejectsTheWrongKeyAndServesTheRightOne() async throws {
        let proxy = PreviewLocalProxy { _ in
            PreviewHTTPResult(status: 200, body: Data("ok".utf8))
        }
        try await proxy.start()
        defer { Task { await proxy.stop() } }
        let port = await proxy.port
        #expect(port != 0)
        let refused = try await text(port: port, path: "/nope/path")
        #expect(refused.status == 404)
        let id = String(repeating: "ef", count: 12)
        let key = await proxy.key
        let ok = try await text(port: port, path: "/\(key)/\(id)/index.html")
        #expect(ok.status == 200)
        #expect(ok.body == "ok")
    }

    @Test func websocketBridgesUnsolicitedTextAndBinaryToSeparateServer() async throws {
        let server = try await PreviewTestServer.start()
        defer { server.listener.cancel() }
        let proxy = PreviewLocalProxy(
            exchange: { _ in PreviewHTTPResult(status: 200, body: Data()) },
            websocket: { path, key, _ in
                #expect(path == "/dsh-link/mobile/preview/" + String(repeating: "ab", count: 12) + "/socket")
                let connection = NWConnection(host: "127.0.0.1", port: server.port, using: .tcp)
                try await PreviewTestServer.connect(connection)
                try await PreviewTestServer.send(
                    connection, Data(("GET \(path) HTTP/1.1\r\nSec-WebSocket-Key: \(key)\r\n\r\n").utf8))
                return connection
            })
        try await proxy.start()
        defer { Task { await proxy.stop() } }
        let url = URL(string: await proxy.localURL(previewID: String(repeating: "ab", count: 12)) + "socket")!
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        components.scheme = "ws"
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 5
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let socket = session.webSocketTask(with: components.url!)
        socket.resume()
        let greeting = try await socket.receive()
        guard case .string(let text) = greeting else {
            Issue.record("Expected server greeting")
            return
        }
        #expect(text == "from separate server")
        try await socket.send(.data(Data([1, 2, 255])))
        let reply = try await socket.receive()
        guard case .data(let bytes) = reply else {
            Issue.record("Expected remote binary reply")
            return
        }
        #expect(bytes == Data([9, 8, 7]))
        await proxy.stop()
        #expect(await proxy.port == 0)
        do {
            _ = try await socket.receive()
            Issue.record("Proxy stop must close active websocket")
        } catch {}
        try await proxy.start()
        #expect(await proxy.port != 0)
    }

    @Test func failedUpstreamReturnsBadGatewayInsteadOfSwitchingProtocols() async throws {
        let proxy = PreviewLocalProxy(
            exchange: { _ in PreviewHTTPResult(status: 200, body: Data()) },
            websocket: { _, _, _ in
                throw URLError(.cannotConnectToHost)
            })
        try await proxy.start()
        defer { Task { await proxy.stop() } }
        var request = URLRequest(url: URL(string: await proxy.localURL(previewID: String(repeating: "ab", count: 12)))!)
        request.setValue("websocket", forHTTPHeaderField: "Upgrade")
        request.setValue("Upgrade", forHTTPHeaderField: "Connection")
        request.setValue("dGhlIHNhbXBsZSBub25jZQ==", forHTTPHeaderField: "Sec-WebSocket-Key")
        let (_, response) = try await URLSession.shared.data(for: request)
        #expect((response as? HTTPURLResponse)?.statusCode == 502)
    }

    @Test func missingWebsocketCapabilityDoesNotEcho() async throws {
        let proxy = PreviewLocalProxy { _ in PreviewHTTPResult(status: 200, body: Data()) }
        try await proxy.start()
        defer { Task { await proxy.stop() } }
        let url = URL(string: await proxy.localURL(previewID: String(repeating: "ab", count: 12)))!
        var request = URLRequest(url: url)
        request.setValue("websocket", forHTTPHeaderField: "Upgrade")
        request.setValue("Upgrade", forHTTPHeaderField: "Connection")
        request.setValue("dGhlIHNhbXBsZSBub25jZQ==", forHTTPHeaderField: "Sec-WebSocket-Key")
        let (_, response) = try await URLSession.shared.data(for: request)
        #expect((response as? HTTPURLResponse)?.statusCode == 501)
    }

    private func text(port: UInt16, path: String) async throws -> (status: Int, body: String) {
        let url = URL(string: "http://127.0.0.1:\(port)\(path)")!
        let (data, response) = try await URLSession.shared.data(from: url)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        return (status, String(data: data, encoding: .utf8) ?? "")
    }
}
