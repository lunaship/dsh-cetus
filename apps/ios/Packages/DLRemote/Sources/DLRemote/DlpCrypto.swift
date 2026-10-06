import CryptoKit
import Foundation

/// DLP/1 byte-exact cryptography (RFC 0001 section 5.2, 5.3, 5.6).
///
/// Ed25519 seeds are the same 32-byte RFC 8032 seeds Node wraps in PKCS#8. Nothing here opens a socket.
public enum DlpCrypto {
    public static let version = DlpWire.version

    public enum ClientKind: String, Sendable, Equatable {
        case device
        case bootstrap

        var byte: UInt8 {
            switch self {
            case .device: return 1
            case .bootstrap: return 2
            }
        }
    }

    public struct BootstrapMaterial: Sendable, Equatable {
        public var bootstrapId: Data
        public var bootstrapKey: Data
    }

    public enum Failure: Error, Equatable {
        case invalidLength(String)
        case invalidBase64URL
        case unsupportedVersion
        case invalidKind
        case invalidTimestamp
        case invalidHKDF
    }

    /// base64url without padding. The encoded length is exact for the input.
    public static func base64URL(_ bytes: Data) -> String {
        bytes.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Decode unpadded base64url. Rejects padding, standard alphabet, and a length that does not round-trip.
    public static func base64URLDecode(_ value: String, expectedLength: Int? = nil) throws -> Data {
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
        guard !value.isEmpty || expectedLength == 0,
            value.unicodeScalars.allSatisfy({ allowed.contains($0) })
        else {
            throw Failure.invalidBase64URL
        }
        var padded = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let remainder = padded.count % 4
        if remainder == 1 { throw Failure.invalidBase64URL }
        if remainder > 0 { padded += String(repeating: "=", count: 4 - remainder) }
        guard let bytes = Data(base64Encoded: padded) else { throw Failure.invalidBase64URL }
        guard base64URL(bytes) == value else { throw Failure.invalidBase64URL }
        if let expectedLength, bytes.count != expectedLength { throw Failure.invalidBase64URL }
        return bytes
    }

    public static func hostPublicKey(seed: Data) throws -> Data {
        try requireCount(seed, 32, "host key seed")
        return Data(try Curve25519.Signing.PrivateKey(rawRepresentation: seed).publicKey.rawRepresentation)
    }

    /// RFC 8032 Ed25519, deterministic. CryptoKit `signature(for:)` is randomized on
    /// Apple platforms, so it cannot reproduce the shared Node vectors.
    public static func sign(seed: Data, transcript: Data) throws -> Data {
        try requireCount(seed, 32, "host key seed")
        return try DlpEd25519.sign(seed: seed, message: transcript)
    }

    public static func verify(publicKey: Data, transcript: Data, signature: Data) -> Bool {
        guard publicKey.count == 32, signature.count == 64,
            let key = try? Curve25519.Signing.PublicKey(rawRepresentation: publicKey)
        else {
            return false
        }
        return key.isValidSignature(signature, for: transcript)
    }

    public static func routeId(hostPub: Data) throws -> Data {
        try requireCount(hostPub, 32, "host public key")
        var input = Data(ascii("DLP1 route"))
        input.append(0)
        input.append(hostPub)
        return Data(SHA256.hash(data: input).prefix(16))
    }

    public static func registerTranscript(challenge: Data, hostPub: Data, version: Int = version) throws -> Data {
        try requireVersion(version)
        try requireCount(challenge, 32, "challenge")
        try requireCount(hostPub, 32, "host public key")
        return prefixed("DLP1 host_register", version: version, parts: [challenge, hostPub])
    }

    public static func acceptTranscript(
        challenge: Data, hostPub: Data, sid: Data, version: Int = version
    ) throws -> Data {
        try requireVersion(version)
        try requireCount(challenge, 32, "challenge")
        try requireCount(hostPub, 32, "host public key")
        try requireCount(sid, 16, "sid")
        return prefixed("DLP1 host_accept", version: version, parts: [challenge, hostPub, sid])
    }

    public static func clientTranscript(
        route: Data,
        kind: ClientKind,
        key: Data,
        timestamp: Int,
        nonce: Data,
        version: Int = version
    ) throws -> Data {
        try requireVersion(version)
        try requireCount(route, 16, "route")
        try requireCount(key, 16, "key")
        try requireCount(nonce, 16, "nonce")
        guard timestamp >= 0 else { throw Failure.invalidTimestamp }
        var stamp = Data(count: 8)
        var value = UInt64(timestamp)
        for offset in stride(from: 7, through: 0, by: -1) {
            stamp[offset] = UInt8(value & 0xff)
            value >>= 8
        }
        return prefixed("DLP1 client_open", version: version, parts: [route, Data([kind.byte]), key, stamp, nonce])
    }

    public static func deviceRelayKey(keySeed: Data, relayHandle: Data) throws -> Data {
        try requireCount(keySeed, 32, "key seed")
        try requireCount(relayHandle, 16, "relay handle")
        var message = Data(ascii("DLP1 device key"))
        message.append(0)
        message.append(relayHandle)
        return Data(HMAC<SHA256>.authenticationCode(for: message, using: SymmetricKey(data: keySeed)))
    }

    public static func clientMac(key: Data, transcript: Data) throws -> Data {
        try requireCount(key, 32, "client key")
        return Data(HMAC<SHA256>.authenticationCode(for: transcript, using: SymmetricKey(data: key)))
    }

    /// HKDF-SHA256, RFC 5869. Output length is exact.
    public static func hkdf(ikm: Data, salt: Data, info: Data, length: Int) throws -> Data {
        guard length >= 1, length <= 255 * 32 else { throw Failure.invalidHKDF }
        let prk = Data(HMAC<SHA256>.authenticationCode(for: ikm, using: SymmetricKey(data: salt)))
        var output = Data()
        output.reserveCapacity(length)
        var previous = Data()
        var counter: UInt8 = 1
        while output.count < length {
            var block = previous
            block.append(info)
            block.append(counter)
            previous = Data(HMAC<SHA256>.authenticationCode(for: block, using: SymmetricKey(data: prk)))
            let need = min(previous.count, length - output.count)
            output.append(previous.prefix(need))
            counter &+= 1
        }
        return output
    }

    public static func bootstrapKeys(seed: Data, route: Data) throws -> BootstrapMaterial {
        try requireCount(seed, 16, "bootstrap seed")
        try requireCount(route, 16, "route")
        return BootstrapMaterial(
            bootstrapId: try hkdf(ikm: seed, salt: route, info: Data(ascii("DLP1 bootstrap id")), length: 16),
            bootstrapKey: try hkdf(ikm: seed, salt: route, info: Data(ascii("DLP1 bootstrap key")), length: 32)
        )
    }

    public static func safeEqual(_ lhs: Data, _ rhs: Data) -> Bool {
        guard lhs.count == rhs.count else { return false }
        var diff: UInt8 = 0
        for index in lhs.indices {
            diff |= lhs[index] ^ rhs[index]
        }
        return diff == 0
    }

    private static func prefixed(_ label: String, version: Int, parts: [Data]) -> Data {
        var out = Data(ascii(label))
        out.append(0)
        out.append(UInt8(version))
        for part in parts { out.append(part) }
        return out
    }

    private static func ascii(_ text: String) -> [UInt8] {
        Array(text.utf8)
    }

    private static func requireCount(_ bytes: Data, _ count: Int, _ label: String) throws {
        if bytes.count != count { throw Failure.invalidLength(label) }
    }

    private static func requireVersion(_ version: Int) throws {
        if version != Self.version { throw Failure.unsupportedVersion }
    }
}
