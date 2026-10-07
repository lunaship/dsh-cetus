import DLSecurity
import Foundation

struct PushGatewayClient: Sendable {
    var session: URLSession = .shared

    func keys(gateway: String) async throws -> [PushGatewayKey] {
        guard let origin = PushRegistrar.gatewayOrigin(gateway), let url = URL(string: origin + "/v1/keys") else {
            throw URLError(.badURL)
        }
        let (data, response) = try await session.data(from: url)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        let decoded = try JSONDecoder().decode(PushGatewayKeysResponse.self, from: data)
        return (decoded.keys ?? []).compactMap { item in
            guard let kid = item.kid, let encoded = item.publicKey,
                let raw = Data(base64Encoded: encoded), raw.count == 32
            else { return nil }
            return PushGatewayKey(kid: kid, publicKey: raw)
        }
    }
}

private struct PushGatewayKeysResponse: Decodable {
    var keys: [PushGatewayKeyItem]?
}

private struct PushGatewayKeyItem: Decodable {
    var kid: String?
    var publicKey: String?
}
