import Foundation

/// DLP/1 protocol constants and control-frame codec (RFC sections 5.1, 5.4, 5.7, 5.8).
///
/// Values match src/remote/wire.js. Do not invent reject or close codes.
public enum DlpWire {
    public static let version = 1

    /// Control text frame, at most 4 KiB (RFC 5.1).
    public static let maxControlBytes = 4 * 1024
    /// Data message read cap is 256 KiB; senders chunk at 64 KiB (RFC 5.4.4).
    public static let maxDataMessageBytes = 256 * 1024
    public static let dataChunkBytes = 64 * 1024

    public static let routeBytes = 16
    public static let sidBytes = 16
    public static let keyBytes = 16
    public static let nonceBytes = 16
    public static let challengeBytes = 32
    public static let hostPubBytes = 32
    public static let macBytes = 32
    public static let signatureBytes = 64

    /// Allowed |ts - now|, also the nonce-cache lifetime (RFC 5.5 steps 3 and 6).
    public static let clockSkewSec = 60
    public static let nonceCacheMax = 10_000

    /// Agent concurrency caps (RFC 5.8).
    public static let deviceMaxStreams = 12
    public static let bootstrapMaxStreams = 4
    public static let hostMaxStreams = 32

    /// First-message timeout. Not configurable.
    public static let firstMessageTimeoutMs = 5_000
    /// Wait for ready after host_accept (RFC 5.5 step 9).
    public static let acceptReadyTimeoutMs = 10_000
    /// Single write timeout (RFC 5.8).
    public static let writeTimeoutMs = 30_000

    /// Control reconnect (RFC 6.7).
    public static let reconnectBaseMs = 1_000
    public static let reconnectMaxMs = 60_000
    public static let reconnectJitter = 0.3
    public static let reconnectStableMs = 60_000
    public static let replacedBackoffMs = 60_000

    /// Agent reject codes (RFC 5.7). Do not add codes.
    public enum Reject {
        public static let badMac = "BAD_MAC"
        public static let unknownKey = "UNKNOWN_KEY"
        public static let clockSkew = "CLOCK_SKEW"
        public static let replay = "REPLAY"
        public static let bootstrapUnknown = "BOOTSTRAP_UNKNOWN"
        public static let bootstrapExpired = "BOOTSTRAP_EXPIRED"
        public static let bootstrapUsed = "BOOTSTRAP_USED"
        public static let deviceLimit = "DEVICE_LIMIT"
        public static let serverBusy = "SERVER_BUSY"
        public static let localUnavailable = "LOCAL_UNAVAILABLE"

        public static let all: Set<String> = [
            badMac, unknownKey, clockSkew, replay,
            bootstrapUnknown, bootstrapExpired, bootstrapUsed,
            deviceLimit, serverBusy, localUnavailable,
        ]
    }

    /// WebSocket close codes (RFC 5.7).
    public enum Close {
        public static let normal = 1000
        public static let goingAway = 1001
        public static let internalError = 1011
        public static let protocolError = 4000
        public static let unsupportedVersion = 4001
        public static let authFailed = 4002
        public static let routeOffline = 4003
        public static let rateLimited = 4004
        public static let serverBusy = 4005
        public static let openTimeout = 4006
        public static let agentRejected = 4007
        public static let idleTimeout = 4008
        public static let lifetimeExceeded = 4009
        public static let replaced = 4010
    }

    /// Parse one control frame: UTF-8, at most 4 KiB, one JSON object, no duplicate keys.
    /// Failure returns nil. Nested duplicate keys and trailing data are rejected.
    public static func parseControlFrame(_ raw: Data) -> [String: DlpJSON]? {
        if raw.isEmpty || raw.count > maxControlBytes { return nil }
        guard let text = String(data: raw, encoding: .utf8), Data(text.utf8) == raw else { return nil }
        var parser = JSONValueParser(text)
        guard let value = try? parser.parseValue(), parser.skipWhitespace(), parser.isAtEnd else { return nil }
        guard case .object(let fields) = value else { return nil }
        return Dictionary(uniqueKeysWithValues: fields.map { ($0.key, $0.value) })
    }

    public static func parseControlFrame(_ text: String) -> [String: DlpJSON]? {
        parseControlFrame(Data(text.utf8))
    }

    /// Encode a control object. Keys keep insertion order. Slashes are not escaped.
    public static func encodeControl(_ fields: [DlpJSON.Field]) -> String {
        var seen = Set<String>()
        var parts: [String] = []
        parts.reserveCapacity(fields.count)
        for field in fields {
            precondition(seen.insert(field.key).inserted, "control object has a duplicate key")
            parts.append(DlpJSON.string(field.key).encoded + ":" + field.value.encoded)
        }
        return "{" + parts.joined(separator: ",") + "}"
    }

    public static func encodeControl(_ object: [String: DlpJSON]) -> String {
        encodeControl(object.map { DlpJSON.Field($0.key, $0.value) })
    }

    /// Sanitize a peer error code before logging: length and alphabet only.
    public static func safeCode(_ value: String?) -> String {
        guard let value, (1...32).contains(value.count),
            value.unicodeScalars.allSatisfy({ ("A"..."Z").contains($0) || $0 == "_" })
        else { return "UNKNOWN" }
        return value
    }

    /// Split an uplink payload into chunks of at most 64 KiB (RFC 5.4.4).
    public static func chunkData(_ bytes: Data, chunkSize: Int = dataChunkBytes) -> [Data] {
        precondition(chunkSize > 0)
        if bytes.isEmpty { return [] }
        var chunks: [Data] = []
        chunks.reserveCapacity((bytes.count + chunkSize - 1) / chunkSize)
        var offset = 0
        while offset < bytes.count {
            let end = min(offset + chunkSize, bytes.count)
            chunks.append(bytes.subdata(in: offset..<end))
            offset = end
        }
        return chunks
    }

    /// A data-phase binary message must be at most 256 KiB. Empty is allowed.
    public static func isValidDataMessage(_ bytes: Data) -> Bool {
        bytes.count <= maxDataMessageBytes
    }
}
