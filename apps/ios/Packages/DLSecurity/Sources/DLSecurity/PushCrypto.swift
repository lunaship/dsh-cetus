import CryptoKit
import Foundation

/// DLPUSH/1 token sealing and content decryption (RFC 0002 §5.3–§5.4).
///
/// The two associated-data prefixes are separate constants. Callers must not
/// build them from one shared function.
public enum PushCrypto {
    public static let tokenInfo = Data("dlpush/1 token".utf8)
    public static let tokenAADPrefix = "dlpush/1 token|"
    public static let contentAADPrefix = "dlpush/1 content|"
    public static let suite = HPKE.Ciphersuite.Curve25519_SHA256_ChachaPoly

    public static func tokenAAD(kid: String) -> Data {
        Data((tokenAADPrefix + kid).utf8)
    }

    public static func contentAAD(deviceID: String) -> Data {
        Data((contentAADPrefix + deviceID).utf8)
    }

    public static func sealToken(plaintext: Data, recipient: Data, kid: String) throws -> PushSealedToken {
        let publicKey = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: recipient)
        var sender = try HPKE.Sender(recipientKey: publicKey, ciphersuite: suite, info: tokenInfo)
        let ciphertext = try sender.seal(plaintext, authenticating: tokenAAD(kid: kid))
        return PushSealedToken(
            v: 1,
            kid: kid,
            enc: base64URL(sender.encapsulatedKey),
            ct: base64URL(ciphertext))
    }

    /// Opens one published HPKE ciphertext. CryptoKit cannot inject the RFC
    /// ephemeral key, so shared vectors are checked by opening, not resealing.
    public static func openToken(
        sealed: PushSealedToken,
        recipientKey: Curve25519.KeyAgreement.PrivateKey,
        info: Data,
        aad: Data
    ) throws -> Data {
        guard sealed.v == 1, !sealed.kid.isEmpty else { throw PushCryptoError.rejected }
        guard let encapsulated = dataFromBase64URL(sealed.enc), encapsulated.count == 32 else {
            throw PushCryptoError.rejected
        }
        guard let ciphertext = dataFromBase64URL(sealed.ct), ciphertext.count >= 16 else {
            throw PushCryptoError.rejected
        }
        var recipient = try HPKE.Recipient(
            privateKey: recipientKey, ciphersuite: suite, info: info, encapsulatedKey: encapsulated)
        return try recipient.open(ciphertext, authenticating: aad)
    }

    static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func dataFromBase64URL(_ value: String) -> Data? {
        guard !value.isEmpty, value.unicodeScalars.allSatisfy({ isBase64URL($0) }) else { return nil }
        var converted = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let remainder = converted.count % 4
        if remainder > 0 { converted.append(String(repeating: "=", count: 4 - remainder)) }
        return Data(base64Encoded: converted)
    }

    private static func isBase64URL(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar {
        case "A"..."Z", "a"..."z", "0"..."9", "-", "_": true
        default: false
        }
    }
}

public enum PushCryptoError: Error {
    case rejected
}

public struct PushSealedToken: Equatable, Sendable {
    var v: Int
    var kid: String
    var enc: String
    var ct: String
}
