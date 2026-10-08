import DLModels
import DLNet
import DLSecurity
import Foundation

struct PushSettingsRegistration {
    var hostID: String
    var pushVersion: Int
    var pairedDeviceID: String?
    var store: HostStore = HostStore()
    var routes: RouteSelector = RouteSelector()
    var keys: PushGatewayClient = PushGatewayClient()
    var secureStore: any SecureStore = KeychainStore(
        service: PushKeyStore.service, accessGroup: PushKeyStore.accessGroup)
    var defaults: UserDefaults = .standard
    var requestToken: @Sendable () async throws -> PushDeviceToken = {
        guard APNsBuild.enabled else { throw PushTokenError.unavailable }
        return try await PushTokenBridge.shared.register()
    }

    func service() -> PushRegistrationService {
        PushRegistrationService(
            client: { [hostID, store, routes] in
                try await PushPluginClient.connect(hostID: hostID, store: store, routes: routes)
            },
            keys: { [keys] gateway in try await keys.keys(gateway: gateway) },
            token: requestToken,
            store: { [secureStore] in PushKeyStore(store: secureStore) },
            metadata: { [defaults, hostID] in
                PushRegistrationMetadataStore(defaults: defaults, hostID: hostID)
            },
            capabilities: { [pushVersion] in
                LocalPushCapabilities(version: pushVersion, apnsBuildEnabled: APNsBuild.enabled)
            },
            deviceID: { [pairedDeviceID] in pairedDeviceID })
    }
}

enum PushPluginClient {
    static func connect(hostID: String, store: HostStore, routes: RouteSelector) async throws -> HostClient {
        guard let host = await store.get(hostId: hostID) else {
            throw HostClientError.transport(URLError(.cannotFindHost))
        }
        guard let token = await store.token(for: hostID), !token.isEmpty else {
            throw HostClientError.unauthorized
        }
        let selection = await routes.select(key: hostID, candidates: RouteSelector.directCandidates(for: host)) {
            address in
            await probe(address: address, fingerprint: host.certFingerprint)
        }
        guard case .direct(let address) = selection, let base = URL(string: address) else {
            await routes.forget(key: hostID)
            throw HostClientError.transport(URLError(.cannotConnectToHost))
        }
        await routes.noteSuccess(key: hostID, address: address)
        return HostClient(baseURL: base, token: token, expectedFingerprint: host.certFingerprint)
    }

    private static func probe(address: String, fingerprint: String) async -> Bool {
        guard let url = URL(string: address) else { return false }
        let delegate = PinnedSessionDelegate(expectedFingerprint: fingerprint)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 1.2
        configuration.timeoutIntervalForResource = 1.2
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 1.2
        let before = delegate.pinFailureCount
        do {
            let (_, response) = try await session.data(for: request)
            session.finishTasksAndInvalidate()
            return response is HTTPURLResponse && delegate.pinFailureCount == before
        } catch {
            session.invalidateAndCancel()
            return false
        }
    }
}
