import DLRemote
import Foundation
import Testing

/// Shared vectors live at `<repo>/testdata/dlp1/vectors.json`.
///
/// This file is `apps/ios/Packages/DLRemote/Tests/DLRemoteTests/DlpVectorTests.swift`,
/// so the repository root is seven directories above `#filePath`.
struct DlpVectorTests {
    private struct VectorFile: Decodable {
        var inputs: Inputs
        var outputs: Outputs
    }

    private struct Inputs: Decodable {
        var hostKeySeed: String
        var ch: String
        var sid: String
        var keySeed: String
        var relayHandle: String
        var bootstrapSeed: String
        var ts: Int
        var nonce: String
    }

    private struct Outputs: Decodable {
        var hostPub: String
        var routeId: String
        var T_register: String
        var sig_register: String
        var T_accept: String
        var sig_accept: String
        var deviceRelayKey: String
        var T_client_device: String
        var mac_device: String
        var bootstrapId: String
        var bootstrapKey: String
        var T_client_bootstrap: String
        var mac_bootstrap: String
        var b64u: B64
    }

    private struct B64: Decodable {
        var routeId: String
        var relayHandle: String
    }

    @Test func hostSignaturesMatchSharedVectors() throws {
        let file = try load()
        let seed = try hex(file.inputs.hostKeySeed)
        let pub = try DlpCrypto.hostPublicKey(seed: seed)
        #expect(hex(pub) == file.outputs.hostPub)
        let route = try DlpCrypto.routeId(hostPub: pub)
        #expect(hex(route) == file.outputs.routeId)
        let register = try DlpCrypto.registerTranscript(challenge: hex(file.inputs.ch), hostPub: pub)
        #expect(hex(register) == file.outputs.T_register)
        let registerSig = try DlpCrypto.sign(seed: seed, transcript: register)
        #expect(hex(registerSig) == file.outputs.sig_register)
        #expect(DlpCrypto.verify(publicKey: pub, transcript: register, signature: registerSig))
        let accept = try DlpCrypto.acceptTranscript(
            challenge: hex(file.inputs.ch), hostPub: pub, sid: hex(file.inputs.sid))
        #expect(hex(accept) == file.outputs.T_accept)
        let acceptSig = try DlpCrypto.sign(seed: seed, transcript: accept)
        #expect(hex(acceptSig) == file.outputs.sig_accept)
        #expect(DlpCrypto.verify(publicKey: pub, transcript: accept, signature: acceptSig))
    }

    @Test func deviceAndBootstrapMacsMatchSharedVectors() throws {
        let file = try load()
        let route = try hex(file.outputs.routeId)
        let handle = try hex(file.inputs.relayHandle)
        let deviceKey = try DlpCrypto.deviceRelayKey(keySeed: hex(file.inputs.keySeed), relayHandle: handle)
        #expect(hex(deviceKey) == file.outputs.deviceRelayKey)
        let deviceTranscript = try DlpCrypto.clientTranscript(
            route: route, kind: .device, key: handle, timestamp: file.inputs.ts, nonce: hex(file.inputs.nonce)
        )
        #expect(hex(deviceTranscript) == file.outputs.T_client_device)
        #expect(hex(try DlpCrypto.clientMac(key: deviceKey, transcript: deviceTranscript)) == file.outputs.mac_device)

        let bootstrap = try DlpCrypto.bootstrapKeys(seed: hex(file.inputs.bootstrapSeed), route: route)
        #expect(bootstrap.bootstrapId.count == 16)
        #expect(bootstrap.bootstrapKey.count == 32)
        #expect(hex(bootstrap.bootstrapId) == file.outputs.bootstrapId)
        #expect(hex(bootstrap.bootstrapKey) == file.outputs.bootstrapKey)
        let bootstrapTranscript = try DlpCrypto.clientTranscript(
            route: route,
            kind: .bootstrap,
            key: bootstrap.bootstrapId,
            timestamp: file.inputs.ts,
            nonce: hex(file.inputs.nonce)
        )
        #expect(hex(bootstrapTranscript) == file.outputs.T_client_bootstrap)
        let bootstrapMac = try DlpCrypto.clientMac(key: bootstrap.bootstrapKey, transcript: bootstrapTranscript)
        #expect(hex(bootstrapMac) == file.outputs.mac_bootstrap)
    }

    @Test func base64URLMatchesSharedVectorsAndRejectsInexactInput() throws {
        let file = try load()
        let route = try hex(file.outputs.routeId)
        let handle = try hex(file.inputs.relayHandle)
        #expect(DlpCrypto.base64URL(route) == file.outputs.b64u.routeId)
        #expect(DlpCrypto.base64URL(handle) == file.outputs.b64u.relayHandle)
        #expect(try DlpCrypto.base64URLDecode(file.outputs.b64u.routeId, expectedLength: 16) == route)
        #expect(try DlpCrypto.base64URLDecode(file.outputs.b64u.relayHandle, expectedLength: 16) == handle)
        #expect(throws: DlpCrypto.Failure.invalidBase64URL) {
            try DlpCrypto.base64URLDecode(file.outputs.b64u.routeId + "=", expectedLength: 16)
        }
        #expect(throws: DlpCrypto.Failure.invalidBase64URL) {
            try DlpCrypto.base64URLDecode(file.outputs.b64u.routeId + "A", expectedLength: 16)
        }
        #expect(throws: DlpCrypto.Failure.invalidBase64URL) {
            try DlpCrypto.base64URLDecode(
                file.outputs.b64u.routeId.replacingOccurrences(of: "-", with: "+"), expectedLength: 16)
        }
    }

    @Test func lengthAndVersionFailuresStayTyped() throws {
        #expect(throws: DlpCrypto.Failure.unsupportedVersion) {
            try DlpCrypto.registerTranscript(
                challenge: Data(repeating: 1, count: 32), hostPub: Data(repeating: 2, count: 32), version: 2)
        }
        #expect(throws: DlpCrypto.Failure.invalidLength("challenge")) {
            try DlpCrypto.registerTranscript(
                challenge: Data(repeating: 1, count: 31), hostPub: Data(repeating: 2, count: 32))
        }
        #expect(throws: DlpCrypto.Failure.invalidTimestamp) {
            try DlpCrypto.clientTranscript(
                route: Data(repeating: 1, count: 16), kind: .device, key: Data(repeating: 2, count: 16), timestamp: -1,
                nonce: Data(repeating: 3, count: 16))
        }
        #expect(throws: DlpCrypto.Failure.invalidHKDF) {
            try DlpCrypto.hkdf(ikm: Data([1]), salt: Data([2]), info: Data([3]), length: 0)
        }
    }

    private func load() throws -> VectorFile {
        let bundle = Bundle(for: DlpVectorBundleToken.self)
        let url = bundle.url(forResource: "vectors", withExtension: "json", subdirectory: "dlp1")
            ?? bundle.url(forResource: "vectors", withExtension: "json")
        guard let url else { throw DlpCrypto.Failure.invalidLength("vectors") }
        return try JSONDecoder().decode(VectorFile.self, from: Data(contentsOf: url))
    }

    private func hex(_ value: String) throws -> Data {
        var out = Data()
        var buffer = ""
        for character in value {
            buffer.append(character)
            if buffer.count == 2 {
                guard let byte = UInt8(buffer, radix: 16) else { throw DlpCrypto.Failure.invalidLength("hex") }
                out.append(byte)
                buffer = ""
            }
        }
        return out
    }

    private func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }
}

private final class DlpVectorBundleToken {}
