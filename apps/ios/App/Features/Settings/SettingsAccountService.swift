import DLModels
import DLNet
import DLSecurity
import Foundation

/// 7.2 / 7.3 的网络与本机存储。测试注入临时目录和假客户端，不碰 Keychain 或真实主机。
struct SettingsAccountService: SettingsAccountServing, Sendable {
    var client: @Sendable () async throws -> HostClient
    var host: @Sendable () async -> PairedHost?
    var rename: @Sendable (String?) async throws -> Void
    var alias: @Sendable () -> String?
    var phoneName: @Sendable () -> String?
    var deleteHost: @Sendable () async throws -> Void
    /// 解绑成功后只丢弃这台电脑的本地草稿（C02 要求 9）；生产传 `ComposerDraftStore.removeHost`。
    var discardDrafts: @Sendable (_ hostID: String) -> Void = { _ in }

    func loadComputer() async -> ComputerAccountSnapshot {
        let current = await host()
        let storedAlias = alias()
        let name = computerDisplayName(
            alias: storedAlias, originalName: current?.name ?? "", hostID: current?.hostId ?? "")
        return ComputerAccountSnapshot(
            displayName: name,
            originalName: current?.name ?? "",
            alias: storedAlias,
            address: current?.primaryUrl ?? "",
            hasTailnet: !(current?.tailnetUrl ?? "").isEmpty,
            hasRelay: current?.remote != nil,
            pairedPhoneName: phoneName())
    }

    func renameComputer(_ draft: String) async -> ComputerRename? {
        let current = await host()
        let renamed = renameComputerLocally(
            alias: alias(), originalName: current?.name ?? "", draft: draft)
        guard let renamed else { return nil }
        do {
            try await rename(renamed.storedAlias)
        } catch {
            return nil
        }
        return renamed
    }

    func unpair() async -> ComputerUnpairOutcome {
        let resolved: SelfRevokeResolution
        do {
            resolved = try await resolveSelf()
        } catch {
            return .kept(.transport)
        }
        guard case .ready(let body, let http) = resolved else { return .kept(.unavailable) }
        do {
            _ = try await http.post(
                RevokeResponse.self, path: "/dsh-link/mobile/revoke", json: body.encoded)
        } catch let error as HostClientError {
            if case .unauthorized = error {
                return await delete(.alreadyUnauthorized)
            }
            return .kept(unpairFailure(for: error))
        } catch {
            return .kept(.transport)
        }
        return await delete(.revoked)
    }

    func diagnostics() async throws -> DiagnosticsReport {
        let http = try await client()
        return try await http.get(DiagnosticsReport.self, path: "/dsh-link/mobile/diagnostics")
    }

    private func resolveSelf() async throws -> SelfRevokeResolution {
        guard let saved = phoneName()?.trimmingCharacters(in: .whitespacesAndNewlines), !saved.isEmpty else {
            return .unavailable
        }
        let http = try await client()
        let devices = try await http.get(DevicesResponse.self, path: "/dsh-link/mobile/devices")
        guard let body = selfRevokeBody(deviceID: selfDeviceID(in: devices.devices ?? [], pairedPhoneName: saved))
        else { return .unavailable }
        return .ready(body, http)
    }

    private enum SelfRevokeResolution {
        case unavailable
        case ready(SelfRevokeBody, HostClient)
    }

    private func delete(_ success: ComputerUnpairOutcome) async -> ComputerUnpairOutcome {
        let hostID = await host()?.hostId
        do {
            try await deleteHost()
            if let hostID, !hostID.isEmpty { discardDrafts(hostID) }
            return success
        } catch {
            return .kept(.storage)
        }
    }
}

struct ComputerAccountSnapshot: Equatable, Sendable {
    var displayName: String
    var originalName: String
    var alias: String?
    var address: String
    var hasTailnet: Bool
    var hasRelay: Bool
    var pairedPhoneName: String?
}

/// 本机别名和配对时的手机名。不进 HostStore JSON，也不进 Keychain。
struct ComputerLocalNames: Sendable {
    var defaults: UserDefaults

    func alias(hostID: String) -> String? {
        let value = defaults.string(forKey: key("alias", hostID))?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let value, !value.isEmpty else { return nil }
        return value
    }

    func setAlias(_ alias: String?, hostID: String) {
        let name = key("alias", hostID)
        if let alias, !alias.isEmpty {
            defaults.set(alias, forKey: name)
        } else {
            defaults.removeObject(forKey: name)
        }
    }

    func phoneName(hostID: String) -> String? {
        let value = defaults.string(forKey: key("phone", hostID))?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let value, !value.isEmpty else { return nil }
        return value
    }

    func setPhoneName(_ name: String, hostID: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        defaults.set(trimmed, forKey: key("phone", hostID))
    }

    private func key(_ name: String, _ hostID: String) -> String {
        "settings.\(name).\(hostID)"
    }
}

/// 配对成功时记下手机名。旧记录没有这个值时，解除配对不会拿电脑名去吊销。
enum PairedPhoneNameStore {
    static func remember(_ name: String, hostID: String, defaults: UserDefaults = .standard) {
        ComputerLocalNames(defaults: defaults).setPhoneName(name, hostID: hostID)
    }
}
