import DLCore
import Foundation
import Network

public struct PreviewHTTPResult: Sendable {
    public var status: Int
    public var contentType: String
    public var body: Data

    public init(status: Int, contentType: String = "text/plain", body: Data) {
        self.status = status
        self.contentType = contentType
        self.body = body
    }
}

/// 只监听 127.0.0.1。路径密钥不对就 404，不把请求交给电脑。
public actor PreviewLocalProxy {
    public let key: String
    public private(set) var port: UInt16 = 0
    private let exchange: @Sendable (String) async -> PreviewHTTPResult
    private let frames: @Sendable (Data) async -> Data
    private var listener: NWListener?
    private let queue = DispatchQueue(label: "dev.deeplinks.preview")

    public init(
        key: String = previewRandomKey(),
        exchange: @escaping @Sendable (String) async -> PreviewHTTPResult,
        frames: @escaping @Sendable (Data) async -> Data = { $0 }
    ) {
        self.key = key
        self.exchange = exchange
        self.frames = frames
    }

    public func start() async throws {
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        parameters.requiredLocalEndpoint = NWEndpoint.hostPort(
            host: .ipv4(IPv4Address.loopback), port: .any)
        let listener = try NWListener(using: parameters)
        self.listener = listener
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let lock = NSLock()
            var resumed = false
            func finish(_ result: Result<Void, Error>) {
                lock.lock()
                defer { lock.unlock() }
                guard !resumed else { return }
                resumed = true
                continuation.resume(with: result)
            }
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    finish(.success(()))
                case .failed(let error):
                    finish(.failure(error))
                default:
                    break
                }
            }
            queue.asyncAfter(deadline: .now() + 5) {
                finish(.failure(URLError(.timedOut)))
            }
            listener.newConnectionHandler = { connection in
                connection.start(queue: queue)
                Task { await self.serve(connection) }
            }
            listener.start(queue: queue)
        }
        port = listener.port?.rawValue ?? 0
    }

    public func stop() {
        listener?.cancel()
        listener = nil
    }

    public func localURL(previewID: String) -> String {
        previewLoopbackURL(port: Int(port), key: key, previewID: previewID)
    }

    private func serve(_ connection: NWConnection) async {
        let received = await receive(connection, minimum: 1, maximum: 64 * 1024)
        guard let head = parsePreviewHead(received) else {
            await send(connection, status: 400, body: Data("bad request".utf8))
            connection.cancel()
            return
        }
        guard let pluginPath = mapPreviewPath(key: key, pathAndQuery: head.target) else {
            await send(connection, status: 404, body: Data())
            connection.cancel()
            return
        }
        if head.websocket {
            await serveWebSocket(connection, head: head, buffered: received)
            return
        }
        let result = await exchange(pluginPath)
        await send(connection, status: result.status, type: result.contentType, body: result.body)
        connection.cancel()
    }

    private func serveWebSocket(_ connection: NWConnection, head: PreviewHTTPHead, buffered: Data) async {
        guard let wsKey = head.webSocketKey, isValidWebSocketKey(wsKey) else {
            await send(connection, status: 400, body: Data())
            connection.cancel()
            return
        }
        let accept = webSocketAccept(wsKey)
        let switching = Data(
            """
            HTTP/1.1 101 Switching Protocols\r
            Upgrade: websocket\r
            Connection: Upgrade\r
            Sec-WebSocket-Accept: \(accept)\r
            \r

            """.utf8)
        await transmit(connection, switching)
        var pending = buffered.dropFirst(head.headerEnd)
        if pending.isEmpty {
            pending = await receive(connection, minimum: 2, maximum: 64 * 1024)
        }
        guard let decoded = readClientFrame(Data(pending)) else {
            connection.cancel()
            return
        }
        let reply = await frames(decoded.frame.payload)
        let text = String(data: reply, encoding: .utf8) ?? ""
        await transmit(connection, serverTextFrame(text))
    }

    private func receive(_ connection: NWConnection, minimum: Int, maximum: Int) async -> Data {
        await withCheckedContinuation { continuation in
            connection.receive(minimumIncompleteLength: minimum, maximumLength: maximum) { data, _, _, _ in
                continuation.resume(returning: data ?? Data())
            }
        }
    }

    private func send(_ connection: NWConnection, status: Int, type: String = "text/plain", body: Data) async {
        let header: String
        if status == 404 {
            header = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        } else if status == 400 {
            header = "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        } else {
            header = "HTTP/1.1 \(status) OK\r\nContent-Type: \(type)\r\n"
                + "Content-Length: \(body.count)\r\nConnection: close\r\n\r\n"
        }
        var bytes = Data(header.utf8)
        if status == 200 || (status != 400 && status != 404) { bytes.append(body) }
        await transmit(connection, bytes)
    }

    private func transmit(_ connection: NWConnection, _ data: Data) async {
        await withCheckedContinuation { continuation in
            connection.send(content: data, completion: .contentProcessed { _ in
                continuation.resume()
            })
        }
    }
}
