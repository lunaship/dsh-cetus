import DLModels
import Foundation

struct SettingsModelsState: Equatable, Sendable {
    var defaults = ConversationDefaults()
    var presets: [AgentPreset] = []
    var modelGroups: [SessionModelGroup] = []
    var balance = BalanceResponse()
    var providers = ProvidersResponse()
    var discovered: [DiscoveredModel] = []
    var apiKey = ""
    var lastBalanceAt: Date?
    var error: SettingsModelsError?
    var settingsWritable = false
}

@MainActor @Observable
final class SettingsModelsModel {
    private let service: any SettingsModelsServing
    var state = SettingsModelsState()

    /// 仅取余额（设置首页「模型与余额」行右侧值用，不拉全量模型列表）。
    func loadBalance(locale: String) async {
        do {
            state.balance = try await service.balance(locale: locale)
        } catch {
            // 拿不到就不显示值，不抛错。
        }
    }

    init(service: any SettingsModelsServing) { self.service = service }

    func load(locale: String, now: Date = Date(), lastBalanceAt: Date? = nil) async {
        let fetchBalance = BalanceAlert.shouldFetch(lastAt: lastBalanceAt, now: now)
        do {
            let settings = try await service.settings()
            let presets = try await service.presets()
            let models = try await service.models()
            if fetchBalance { state.balance = try await service.balance(locale: locale) }
            let providers = try await service.providers()
            state.defaults = ConversationDefaults.from(namespaces: settings.namespaces ?? [])
            state.presets = presets
            state.modelGroups = models.groups ?? []
            state.providers = providers
            if fetchBalance { state.lastBalanceAt = now }
            state.settingsWritable = settings.writable == true
            state.apiKey = ""
            state.discovered = []
            state.error = nil
        } catch let error as SettingsModelsError {
            state.error = error
        } catch {
            state.error = .rejected(nil)
        }
    }

    func savePreset(_ id: String) async {
        guard state.presets.contains(where: { $0.id == id }) else {
            state.error = .rejected("unknown-preset")
            return
        }
        await save("agent-presets", ["default": id])
    }

    func saveDefaultModel(provider: String, model: String) async {
        guard catalogContains(provider: provider, model: model) else {
            state.error = .rejected("unknown-model")
            return
        }
        await save("agent-default-model", ["provider": provider, "model": model])
    }

    func saveReasoningEffort(_ effort: String) async {
        let trimmed = effort.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let provider = state.defaults.modelProvider, let model = state.defaults.model,
            effortChoices(provider: provider, model: model).contains(trimmed)
        else {
            state.error = .rejected("unknown-model")
            return
        }
        await save(
            "agent-default-model", ["provider": provider, "model": model, "reasoningEffort": trimmed])
    }

    func addProvider(id: String) async {
        guard state.providers.addable?.contains(where: { $0.provider == id }) == true else {
            state.error = .notAddable
            return
        }
        let key = trimmedKey
        if !key.isEmpty, let problem = ProviderInput.apiKeyProblem(key) {
            state.error = problem == "empty" ? .emptyKey : .invalidKey
            return
        }
        do {
            state.providers = try await service.addProvider(id: id, apiKey: key.isEmpty ? nil : key)
            clearKey()
        } catch let error as SettingsModelsError {
            state.error = error
        } catch { state.error = .rejected(nil) }
    }

    func replaceCredential(provider: String) async {
        guard let row = row(provider), row.credential?.writable == true else {
            state.error = .rejected("credential-read-only")
            return
        }
        let key = trimmedKey
        if let problem = ProviderInput.apiKeyProblem(key) {
            state.error = problem == "empty" ? .emptyKey : .invalidKey
            return
        }
        do {
            state.providers = try await service.replaceCredential(provider: provider, apiKey: key)
            clearKey()
        } catch let error as SettingsModelsError {
            state.error = error
        } catch { state.error = .rejected(nil) }
    }

    func discover(provider: String) async {
        guard let row = row(provider), row.canDiscover == true else {
            state.error = .rejected("not-discoverable")
            return
        }
        do {
            let found = try await service.discover(provider: provider).models ?? []
            let known = Set((row.models ?? []).compactMap(\.id))
            state.discovered = found.filter { model in
                guard let id = model.id, !id.isEmpty else { return false }
                return !known.contains(id)
            }
            state.error = nil
        } catch let error as SettingsModelsError {
            state.error = error
        } catch { state.error = .rejected(nil) }
    }

    func saveDiscovered(provider: String, selected: Set<String>, remove: Set<String> = []) async {
        guard let row = row(provider) else {
            state.error = .rejected("unknown-provider")
            return
        }
        guard row.modelsEditable == true else {
            state.error = .modelsInherited
            return
        }
        let add = state.discovered.filter { selected.contains($0.id ?? "") }
        let current = Set((row.models ?? []).compactMap(\.id))
        let removing = remove.intersection(current)
        guard !add.isEmpty || !removing.isEmpty else {
            state.error = .rejected("empty")
            return
        }
        if current.subtracting(removing).isEmpty && add.isEmpty {
            state.error = .emptyModels
            return
        }
        do {
            state.providers = try await service.saveModels(
                provider: provider, add: add, remove: Array(removing).sorted())
            state.discovered = []
            state.error = nil
        } catch let error as SettingsModelsError {
            state.error = error
        } catch { state.error = .rejected(nil) }
    }

    func validateAlert(enabled: Bool, amount: String) -> Bool {
        guard enabled else {
            state.error = nil
            return true
        }
        guard BalanceAlert.parseAmount(amount) != nil else {
            state.error = .invalidAmount
            return false
        }
        state.error = nil
        return true
    }

    func showsBalanceNotice(enabled: Bool, amount: String) -> Bool {
        BalanceAlert.belowThreshold(
            status: state.balance.status,
            topUp: state.balance.wallets?.first?.balance,
            preference: BalanceAlertPreference(enabled: enabled, threshold: amount))
    }

    var displayedKey: String { state.apiKey }

    private var trimmedKey: String { state.apiKey.trimmingCharacters(in: .whitespacesAndNewlines) }

    private func clearKey() {
        state.apiKey = ""
        state.error = nil
    }

    private func row(_ provider: String) -> ProviderRow? {
        state.providers.providers?.first { $0.provider == provider }
    }

    private func catalogContains(provider: String, model: String) -> Bool {
        modelOption(provider: provider, model: model) != nil
    }

    private func effortChoices(provider: String, model: String) -> [String] {
        modelOption(provider: provider, model: model)?.reasoningEfforts ?? []
    }

    private func modelOption(provider: String, model: String) -> SessionModelOption? {
        state.modelGroups.first { $0.provider == provider }?.models?.first { $0.id == model }
    }

    private func save(_ ns: String, _ patch: [String: String]) async {
        guard state.settingsWritable else {
            state.error = .notWritable
            return
        }
        do {
            let updated = try await service.update(
                ns: ns, patch: patch, expectedRevision: state.defaults.revisions[ns])
            state.defaults = merged(state.defaults, updated: updated)
            state.error = nil
        } catch let error as SettingsModelsError {
            state.error = error
        } catch { state.error = .rejected(nil) }
    }

    private func merged(_ previous: ConversationDefaults, updated: MobileSettingsNamespace) -> ConversationDefaults {
        let incoming = ConversationDefaults.from(namespaces: [updated])
        var value = previous
        let name = updated.ns ?? ""
        if let revision = updated.revision { value.revisions[name] = revision }
        switch name {
        case "agent-presets": value.agentPreset = incoming.agentPreset
        case "permission": value.permissionPreset = incoming.permissionPreset
        case "ui-conversation": value.busyEnter = incoming.busyEnter
        case "agent-default-model":
            value.modelProvider = incoming.modelProvider
            value.model = incoming.model
            value.reasoningEffort = incoming.reasoningEffort
        default: break
        }
        return value
    }
}
