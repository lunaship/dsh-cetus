import CryptoKit
import DLModels
import DLNet
import DLSecurity
import Foundation

struct PushRegistrationService: Sendable {
    var client: @Sendable () async throws -> HostClient
    var keys: @Sendable (String) async throws -> [PushGatewayKey]
    var token: @Sendable () async throws -> PushDeviceToken
    var store: @Sendable () -> PushKeyStore
    var metadata: @Sendable () -> PushRegistrationMetadataStore
    var capabilities: @Sendable () -> LocalPushCapabilities
    var now: @Sendable () -> Date = { Date() }

    func sync(enabled: Bool, preferences: PushPreferences) async -> PushRegistrationOutcome {
        let capabilities = capabilities()
        guard capabilities.canEnable else {
            try? clearLocal()
            return .unavailable(capabilities.apnsBuildEnabled ? .missingCapability : .unavailable)
        }
        guard enabled else {
            return await unregister()
        }
        let http: HostClient
        do {
            http = try await client()
        } catch {
            return .failed(Self.failure(error))
        }
        let gatewayKeys: [PushGatewayKey]
        let device: PushDeviceToken
        do {
            async let loadedKeys = keys(PushRegistrar.officialGateway)
            async let loadedToken = token()
            gatewayKeys = try await loadedKeys
            device = try await loadedToken
        } catch {
            return .failed(Self.failure(error))
        }
        guard let key = PushRegistrar.currentKey(in: gatewayKeys) else { return .failed(.rejected) }
        let stored = metadata().record
        let rotating = stored?.kid != key.kid || stored?.tokenFingerprint != PushRegistrar.fingerprint(device.bytes)
        let contentKey = (rotating ? nil : (try? store().load())) ?? PushContentKey.generate()
        guard
            let draft = PushRegistrar.prepare(
                token: device,
                key: key,
                prefs: preferences,
                contentKey: SymmetricKey(data: contentKey))
        else { return .failed(.rejected) }
        let action = PushRegistrationReconciler.action(
            enabled: true,
            canRegister: true,
            stored: stored,
            prepared: draft.body)
        switch action {
        case .none:
            guard stored?.tokenFingerprint == draft.tokenFingerprint else { return .failed(.rejected) }
            return .registered
        case .unregister:
            return await unregister()
        case .register(let body):
            do {
                try store().save(draft.contentKey)
                _ = try await http.postJSONData(
                    path: "/dsh-link/mobile/push/register",
                    body: PushRegistrationWire.json(body, contentKey: draft.contentKey))
                metadata().record = PushRegistrationRecord(
                    gateway: body.gateway,
                    kid: body.kid,
                    sealedFingerprint: PushRegistrar.fingerprint(body.sealed),
                    tokenFingerprint: draft.tokenFingerprint,
                    prefs: body.prefs)
                return .registered
            } catch {
                try? clearLocal()
                return .failed(Self.failure(error))
            }
        }
    }

    func unregister() async -> PushRegistrationOutcome {
        let hadRecord = metadata().record != nil
        do {
            if hadRecord {
                let http = try await client()
                _ = try await http.delete(path: "/dsh-link/mobile/push/register")
            }
            try clearLocal()
            return .disabled
        } catch {
            try? clearLocal()
            return .failed(Self.failure(error))
        }
    }

    private func clearLocal() throws {
        try store().remove()
        metadata().record = nil
    }

    private static func failure(_ error: any Error) -> PushRegistrationFailure {
        guard let error = error as? HostClientError else { return .network }
        switch error {
        case .certificateChanged: return .certificateChanged
        case .capabilityMissing: return .missingCapability
        case .transport: return .network
        case .unauthorized, .forbidden, .sessionBusy, .conflict, .server, .decoding: return .rejected
        }
    }
}

enum PushRegistrationOutcome: Equatable, Sendable {
    case registered
    case disabled
    case unavailable(PushRegistrationFailure)
    case failed(PushRegistrationFailure)
}

struct PushRegistrationMetadataStore: Sendable {
    var defaults: UserDefaults
    var hostID: String

    var record: PushRegistrationRecord? {
        get {
            guard let data = defaults.data(forKey: key) else { return nil }
            return try? JSONDecoder().decode(PushRegistrationRecord.self, from: data)
        }
        nonmutating set {
            if let value = newValue, let data = try? JSONEncoder().encode(value) {
                defaults.set(data, forKey: key)
            } else {
                defaults.removeObject(forKey: key)
            }
        }
    }

    private var key: String { "push.registration.\(hostID)" }
}
