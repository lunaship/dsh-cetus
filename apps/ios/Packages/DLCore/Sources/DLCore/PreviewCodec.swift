import CryptoKit
import Foundation

public struct PreviewHTTPHead: Equatable, Sendable {
    public var method: String
    public var target: String
    public var contentLength: Int
    public var websocket: Bool
    public var webSocketKey: String?
    public var headerEnd: Int

    public init(
        method: String, target: String, contentLength: Int, websocket: Bool, webSocketKey: String?, headerEnd: Int
    ) {
        self.method = method
        self.target = target
        self.contentLength = contentLength
        self.websocket = websocket
        self.webSocketKey = webSocketKey
        self.headerEnd = headerEnd
    }
}

public func parsePreviewHead(_ data: Data) -> PreviewHTTPHead? {
    guard let marker = data.range(of: Data([13, 10, 13, 10])) else { return nil }
    let headerBytes = data.subdata(in: 0..<marker.lowerBound)
    guard let text = String(data: headerBytes, encoding: .isoLatin1) else { return nil }
    let lines = text.components(separatedBy: "\r\n")
    guard let request = lines.first else { return nil }
    let fields = request.split(separator: " ", omittingEmptySubsequences: true)
    guard fields.count >= 2 else { return nil }
    var headers: [String: String] = [:]
    for line in lines.dropFirst() {
        guard let colon = line.firstIndex(of: ":") else { continue }
        let name = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
        let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
        headers[name] = value
    }
    let length = Int(headers["content-length"] ?? "") ?? 0
    let upgrade = headers["upgrade"]?.lowercased() == "websocket"
    return PreviewHTTPHead(
        method: String(fields[0]),
        target: String(fields[1]),
        contentLength: max(0, length),
        websocket: upgrade,
        webSocketKey: headers["sec-websocket-key"],
        headerEnd: marker.upperBound)
}

public func isValidWebSocketKey(_ key: String) -> Bool {
    guard let data = Data(base64Encoded: key) else { return false }
    return data.count == 16
}

public func webSocketAccept(_ key: String) -> String {
    let material = Data((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").utf8)
    return Data(Insecure.SHA1.hash(data: material)).base64EncodedString()
}

public struct PreviewClientFrame: Equatable, Sendable {
    public var opcode: UInt8
    public var payload: Data

    public init(opcode: UInt8, payload: Data) {
        self.opcode = opcode
        self.payload = payload
    }
}

/// 读一帧已掩码的客户端数据。帧不完整时返回 nil。
public func readClientFrame(_ data: Data) -> (frame: PreviewClientFrame, consumed: Int)? {
    guard data.count >= 2 else { return nil }
    let opcode = data[0] & 0x0F
    let masked = (data[1] & 0x80) != 0
    let lenCode = Int(data[1] & 0x7F)
    var offset = 2
    let length: Int
    if lenCode == 126 {
        guard data.count >= 4 else { return nil }
        length = (Int(data[2]) << 8) | Int(data[3])
        offset = 4
    } else if lenCode == 127 {
        return nil
    } else {
        length = lenCode
    }
    let maskLength = masked ? 4 : 0
    guard data.count >= offset + maskLength + length else { return nil }
    let raw = [UInt8](data[(offset + maskLength)..<(offset + maskLength + length)])
    let payload: Data
    if masked {
        let mask = [UInt8](data[offset..<(offset + 4)])
        payload = Data(raw.enumerated().map { index, byte in byte ^ mask[index % 4] })
    } else {
        payload = Data(raw)
    }
    return (PreviewClientFrame(opcode: opcode, payload: payload), offset + maskLength + length)
}

public func serverTextFrame(_ text: String) -> Data {
    let payload = Data(text.utf8)
    var frame = Data([0x81, UInt8(payload.count)])
    frame.append(payload)
    return frame
}
