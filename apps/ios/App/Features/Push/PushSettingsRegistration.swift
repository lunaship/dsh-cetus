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
        // §15.2：直连优先，不可达且有远程能力时走远程。推送注册也必须能走远程 ——
        // 否则手机在外网换了 APNs token 就注册不上，任务通知会静默失效。
        guard let connection = await HostConnectionFactory.open(host: host, token: token, routes: routes) else {
            throw HostClientError.transport(URLError(.cannotConnectToHost))
        }
        if let address = connection.directAddress {
            await routes.noteSuccess(key: hostID, address: address)
        }
        return connection.client
    }

    private static func probe(address: String, fingerprint: String) async -> Bool {
        await HostReachability.probe(address: address, fingerprint: fingerprint)
    }
}
