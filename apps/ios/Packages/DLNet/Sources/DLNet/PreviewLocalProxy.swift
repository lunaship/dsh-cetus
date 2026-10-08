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

/// An opener must return a started connection with the host upgrade request already sent.
public typealias PreviewWebSocketOpener = @Sendable (String, String, String?) async throws -> NWConnection

/// Only listens on loopback; forwards approved IDs after validating the per-listener path key.
public actor PreviewLocalProxy {
    public let key: String
    public private(set) var port: UInt16 = 0
    private let exchange: @Sendable (String) async -> PreviewHTTPResult
    private let websocket: PreviewWebSocketOpener?
    private var listener: NWListener?
    private var connections: [UUID: NWConnection] = [:]
    private var tasks: [UUID: Task<Void, Never>] = [:]
    private let queue = DispatchQueue(label: "dev.deeplinks.preview")
    private var generation = 0

    public init(
        key: String = previewRandomKey(),
        exchange: @escaping @Sendable (String) async -> PreviewHTTPResult,
        websocket: PreviewWebSocketOpener? = nil
    ) {
        self.key = key
        self.exchange = exchange
        self.websocket = websocket
    }

    public func start() async throws {
        guard listener == nil else { return }
        generation += 1
        let currentGeneration = generation
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        parameters.requiredLocalEndpoint = NWEndpoint.hostPort(host: .ipv4(IPv4Address.loopback), port: .any)
        let listener = try NWListener(using: parameters)
        self.listener = listener
        do {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                let completion = PreviewCompletion(continuation)
                listener.stateUpdateHandler = { state in
                    switch state {
                    case .ready: completion.finish(.success(()))
                    case .failed(let error): completion.finish(.failure(error))
                    case .cancelled: completion.finish(.failure(CancellationError()))
                    default: break
                    }
                }
                queue.asyncAfter(deadline: .now() + 5) {
                    if completion.finish(.failure(URLError(.timedOut))) { listener.cancel() }
                }
                listener.newConnectionHandler = { connection in
                    Task { await self.accept(connection, generation: currentGeneration) }
                }
                listener.start(queue: queue)
            }
            guard generation == currentGeneration else { throw CancellationError() }
            port = listener.port?.rawValue ?? 0
        } catch {
            listener.cancel()
            if generation == currentGeneration {
                self.listener = nil
                port = 0
            }
            throw error
        }
    }

    public func stop() {
        generation += 1
        listener?.cancel()
        listener = nil
        port = 0
        for task in tasks.values { task.cancel() }
        for connection in connections.values { connection.cancel() }
        tasks.removeAll()
        connections.removeAll()
    }

    public func localURL(previewID: String) -> String {
        previewLoopbackURL(port: Int(port), key: key, previewID: previewID)
    }

    private func accept(_ connection: NWConnection, generation: Int) {
        guard self.generation == generation, listener != nil else {
            connection.cancel()
            return
        }
        let id = UUID()
        connections[id] = connection
        connection.start(queue: queue)
        tasks[id] = Task {
            await serve(connection, generation: generation)
            connections.removeValue(forKey: id)
            tasks.removeValue(forKey: id)
            connection.cancel()
        }
    }

    private func serve(_ connection: NWConnection, generation: Int) async {
        do {
            var received = Data()
            while received.range(of: Data([13, 10, 13, 10])) == nil {
                let chunk = try await Self.receive(connection, timeout: 12)
                guard !chunk.isEmpty, received.count + chunk.count <= 64 * 1024 else {
                    throw URLError(.badServerResponse)
                }
                received.append(chunk)
            }
            guard let head = parsePreviewHead(received) else { throw URLError(.badServerResponse) }
            guard let pluginPath = mapPreviewPath(key: key, pathAndQuery: head.target) else {
                try await Self.send(connection, status: 404, body: Data())
                return
            }
            if head.websocket {
                await serveWebSocket(
                    connection, head: head, buffered: received, path: pluginPath, generation: generation)
            } else {
                guard head.method == "GET" else {
                    try await Self.send(connection, status: 405, body: Data())
                    return
                }
                let result = await exchange(pluginPath)
                try Task.checkCancellation()
                try await Self.send(connection, status: result.status, type: result.contentType, body: result.body)
            }
        } catch {
            try? await Self.send(connection, status: 400, body: Data())
        }
    }

    private func serveWebSocket(
        _ downstream: NWConnection, head: PreviewHTTPHead, buffered: Data, path: String, generation: Int
    ) async {
        guard head.method == "GET", let wsKey = head.webSocketKey, isValidWebSocketKey(wsKey) else {
            try? await Self.send(downstream, status: 400, body: Data())
            return
        }
        guard let websocket else {
            try? await Self.send(downstream, status: 501, body: Data())
            return
        }
        var upgraded = false
        do {
            let protocols = headerValue("sec-websocket-protocol", from: buffered, end: head.headerEnd)
            let upstream = try await websocket(path, wsKey, protocols)
            let id = UUID()
            guard self.generation == generation, !Task.isCancelled else {
                upstream.cancel()
                return
            }
            connections[id] = upstream
            defer {
                connections.removeValue(forKey: id)
                upstream.cancel()
            }
            var response = Data()
            while response.range(of: Data([13, 10, 13, 10])) == nil {
                let chunk = try await Self.receive(upstream, timeout: 12)
                guard !chunk.isEmpty, response.count + chunk.count <= 64 * 1024 else {
                    throw URLError(.badServerResponse)
                }
                response.append(chunk)
            }
            guard let end = response.range(of: Data([13, 10, 13, 10]))?.upperBound,
                let first = String(data: response.prefix(end), encoding: .isoLatin1)?.components(separatedBy: "\r\n")
                    .first,
                first.split(separator: " ").dropFirst().first == "101",
                headerValue("sec-websocket-accept", from: response, end: end) == webSocketAccept(wsKey)
            else { throw URLError(.badServerResponse) }
            try await Self.transmit(downstream, response)
            upgraded = true
            let initialFrames = Data(buffered.dropFirst(head.headerEnd))
            if !initialFrames.isEmpty { try await Self.transmit(upstream, initialFrames) }
            // Raw bytes preserve binary, fragmentation, control frames and unsolicited server messages.
            await withTaskGroup(of: Void.self) { group in
                group.addTask { await Self.pipe(downstream, to: upstream) }
                group.addTask { await Self.pipe(upstream, to: downstream) }
                await group.next()
                upstream.cancel()
                downstream.cancel()
                group.cancelAll()
            }
        } catch {
            if !upgraded { try? await Self.send(downstream, status: 502, body: Data()) }
        }
    }

    private func headerValue(_ name: String, from bytes: Data, end: Int) -> String? {
        guard let text = String(data: bytes.prefix(end), encoding: .isoLatin1) else { return nil }
        for line in text.components(separatedBy: "\r\n").dropFirst() {
            guard let colon = line.firstIndex(of: ":"), line[..<colon].lowercased() == name else { continue }
            return line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
        }
        return nil
    }

    private static func pipe(_ source: NWConnection, to destination: NWConnection) async {
        do {
            while !Task.isCancelled {
                let bytes = try await receive(source, timeout: 120)
                if bytes.isEmpty { return }
                try await transmit(destination, bytes)
            }
        } catch {}
    }

    private static func receive(_ connection: NWConnection, timeout: TimeInterval) async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            let completion = PreviewCompletion<Data>(continuation)
            connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, complete, error in
                if let error { completion.finish(.failure(error)) } else { completion.finish(.success(data ?? Data())) }
                if complete { connection.cancel() }
            }
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) {
                if completion.finish(.failure(URLError(.timedOut))) { connection.cancel() }
            }
        }
    }

    private static func send(_ connection: NWConnection, status: Int, type: String = "text/plain", body: Data)
        async throws
    {
        let safeType = type.replacingOccurrences(of: "\r", with: "").replacingOccurrences(of: "\n", with: "")
        var bytes = Data(
            ("HTTP/1.1 \(status) Response\r\nContent-Type: \(safeType)\r\n"
                + "Content-Length: \(body.count)\r\nConnection: close\r\n\r\n").utf8)
        bytes.append(body)
        try await transmit(connection, bytes)
    }

    private static func transmit(_ connection: NWConnection, _ data: Data) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(
                content: data,
                completion: .contentProcessed { error in
                    if let error { continuation.resume(throwing: error) } else { continuation.resume() }
                })
        }
    }
}
