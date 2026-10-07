import DLModels
import Foundation
import Testing

@testable import Cetus

@MainActor @Suite struct SettingsModelsTests {
    @Test func loadKeepsServerValuesAndClearsTheKey() async {
        let script = Script(
            settings: view(
                writable: true,
                namespaces: [
                    namespace("agent-presets", ["default": "ptc"], revision: 3),
                    namespace("permission", ["defaultPreset": "read-only"], revision: 4),
                    namespace("ui-conversation", ["busyEnter": "newline"], revision: 5),
                    namespace("agent-default-model", ["provider": "openai", "model": "gpt"], revision: 6),
                ]),
            presets: [AgentPreset(id: "ptc", name: "Code")],
            models: SessionModelsResponse(groups: [group("openai", "gpt")]),
            balance: BalanceResponse(status: .ready, wallets: [Wallet(currency: "CNY", balance: "1.50")]),
            providers: directory(writable: true))
        let model = SettingsModelsModel(service: script)
        model.state.apiKey = "stored-secret"
        await model.load(locale: "zh-Hans")
        #expect(model.state.defaults.agentPreset == "ptc")
        #expect(model.state.defaults.permissionPreset == "read-only")
        #expect(model.state.defaults.busyEnter == "newline")
        #expect(model.state.defaults.revisions["permission"] == 4)
        #expect(model.state.apiKey == "")
        #expect(model.state.settingsWritable)
        #expect(model.state.error == nil)
    }

    @Test func saveSendsTheReadRevisionAndKeepsOtherNamespaces() async {
        let script = Script(
            settings: view(
                writable: true,
                namespaces: [
                    namespace("agent-presets", ["default": "standard"], revision: 2),
                    namespace("permission", ["defaultPreset": "workspace-write"], revision: 9),
                ]),
            presets: [AgentPreset(id: "standard"), AgentPreset(id: "ptc")],
            update: namespace("agent-presets", ["default": "server-choice"], revision: 3))
        let model = SettingsModelsModel(service: script)
        await model.load(locale: "en")
        await model.savePreset("ptc")
        let sent = await script.updates
        #expect(sent == [Update("agent-presets", ["default": "ptc"], 2)])
        #expect(model.state.defaults.agentPreset == "server-choice")
        #expect(model.state.defaults.permissionPreset == "workspace-write")
        #expect(model.state.defaults.revisions["permission"] == 9)
        #expect(model.state.defaults.revisions["agent-presets"] == 3)
    }

    @Test func unknownPresetAndReadOnlySettingsDoNotWrite() async {
        let script = Script(settings: view(writable: false), presets: [AgentPreset(id: "standard")])
        let model = SettingsModelsModel(service: script)
        await model.load(locale: "en")
        await model.savePreset("missing")
        #expect(model.state.error == .rejected("unknown-preset"))
        await model.savePreset("standard")
        #expect(model.state.error == .notWritable)
        #expect(await script.updates.isEmpty)
    }

    @Test func conflictStaysOnTheReadValue() async {
        let script = Script(
            settings: view(
                writable: true,
                namespaces: [
                    namespace("agent-presets", ["default": "standard"], revision: 1)
                ]),
            presets: [AgentPreset(id: "standard"), AgentPreset(id: "ptc")],
            updateError: .conflict("conflict"))
        let model = SettingsModelsModel(service: script)
        await model.load(locale: "en")
        await model.savePreset("ptc")
        #expect(model.state.error == .conflict("conflict"))
        #expect(model.state.defaults.agentPreset == "standard")
        #expect(model.state.defaults.revisions["agent-presets"] == 1)
    }

    @Test func defaultModelMustComeFromTheCatalog() async {
        let script = Script(
            settings: view(writable: true),
            models: SessionModelsResponse(groups: [group("openai", "gpt")]),
            update: namespace("agent-default-model", ["provider": "openai", "model": "gpt"], revision: 1))
        let model = SettingsModelsModel(service: script)
        await model.load(locale: "en")
        await model.saveDefaultModel(provider: "openai", model: "missing")
        #expect(model.state.error == .rejected("unknown-model"))
        await model.saveDefaultModel(provider: "openai", model: "gpt")
        #expect(await script.updates == [Update("agent-default-model", ["provider": "openai", "model": "gpt"], nil)])
        #expect(model.state.defaults.reasoningEffort == nil)
        await model.saveReasoningEffort("missing")
        #expect(model.state.error == .rejected("unknown-model"))
        await model.saveReasoningEffort("high")
        #expect(
            await script.updates
                == [
                    Update("agent-default-model", ["provider": "openai", "model": "gpt"], nil),
                    Update(
                        "agent-default-model", ["provider": "openai", "model": "gpt", "reasoningEffort": "high"],
                        1),
                ])
        #expect(model.state.defaults.model == "gpt")
    }

    @Test func credentialStartsEmptyAndIsNeverEchoed() async {
        let row = ProviderRow(
            provider: "openai", credential: CredentialStatus(configured: true, writable: true, source: "settings"))
        let script = Script(
            providers: ProvidersResponse(writable: true, providers: [row], addable: []),
            written: ProvidersResponse(
                ok: true, writable: true,
                providers: [
                    ProviderRow(
                        provider: "openai",
                        credential: CredentialStatus(configured: true, writable: true, source: "settings"))
                ]))
        let model = SettingsModelsModel(service: script)
        await model.load(locale: "en")
        #expect(model.state.apiKey == "")
        await model.replaceCredential(provider: "openai")
        #expect(model.state.error == .emptyKey)
        model.state.apiKey = "  sk-new  "
        await model.replaceCredential(provider: "openai")
        let credentials = await script.credentials
        #expect(credentials.count == 1)
        #expect(credentials.first?.0 == "openai")
        #expect(credentials.first?.1 == "sk-new")
        #expect(model.state.apiKey == "")
        #expect(model.displayedKey == "")
        model.state.apiKey = "OPENAI_API_KEY=sk-new"
        await model.replaceCredential(provider: "openai")
        #expect(model.state.error == .invalidKey)
        let unchanged = await script.credentials
        #expect(unchanged.count == 1)
        #expect(unchanged.first?.1 == "sk-new")
    }

    @Test func addRejectsProvidersOutsideTheCatalog() async {
        let script = Script(
            providers: ProvidersResponse(
                writable: true, providers: [], addable: [AddableProvider(provider: "openai", displayName: "OpenAI")]))
        let model = SettingsModelsModel(service: script)
        await model.load(locale: "en")
        await model.addProvider(id: "custom")
        #expect(model.state.error == .notAddable)
        #expect(await script.added.isEmpty)
        await model.addProvider(id: "openai")
        let added = await script.added
        #expect(added.count == 1)
        #expect(added.first?.0 == "openai")
        #expect(added.first?.1 == nil)
    }

    @Test func discoverFiltersKnownModelsAndRefusesInheritedWrites() async {
        let row = ProviderRow(
            provider: "openai", models: [ProviderModel(id: "known")], modelsEditable: false, canDiscover: true)
        let script = Script(
            providers: ProvidersResponse(writable: true, providers: [row]),
            discovered: DiscoverModelsResponse(models: [
                DiscoveredModel(id: "known"), DiscoveredModel(id: "new"), DiscoveredModel(id: ""),
            ]))
        let model = SettingsModelsModel(service: script)
        await model.load(locale: "en")
        await model.discover(provider: "openai")
        #expect(model.state.discovered.map(\.id) == ["new"])
        await model.saveDiscovered(provider: "openai", selected: ["new"])
        #expect(model.state.error == .modelsInherited)
        #expect(await script.modelWrites.isEmpty)
    }

    @Test func modelRemovalCannotLeaveZero() async {
        let row = ProviderRow(
            provider: "openai", models: [ProviderModel(id: "only")], modelsEditable: true, canDiscover: true)
        let script = Script(providers: ProvidersResponse(writable: true, providers: [row]))
        let model = SettingsModelsModel(service: script)
        await model.load(locale: "en")
        await model.saveDiscovered(provider: "openai", selected: [], remove: ["only"])
        #expect(model.state.error == .emptyModels)
        #expect(await script.modelWrites.isEmpty)
    }

    @Test func balanceNoticeUsesReadyTopUpOnly() {
        let on = BalanceAlertPreference(enabled: true, threshold: "10.00")
        #expect(BalanceAlert.belowThreshold(status: .ready, topUp: "9.99", preference: on))
        #expect(!BalanceAlert.belowThreshold(status: .ready, topUp: "10.00", preference: on))
        #expect(!BalanceAlert.belowThreshold(status: .ready, topUp: "10.01", preference: on))
        #expect(!BalanceAlert.belowThreshold(status: .signedOut, topUp: "0", preference: on))
        #expect(!BalanceAlert.belowThreshold(status: .failed, topUp: "0", preference: on))
        #expect(!BalanceAlert.belowThreshold(status: .unavailable, topUp: "0", preference: on))
        #expect(!BalanceAlert.belowThreshold(status: .ready, topUp: "not-a-number", preference: on))
        #expect(BalanceAlert.parseAmount(" 12.50 ") == "12.50")
        #expect(BalanceAlert.parseAmount("-1") == nil)
        #expect(BalanceAlert.parseAmount("1.2.3") == nil)
        let now = Date(timeIntervalSince1970: 1_000)
        #expect(BalanceAlert.shouldFetch(lastAt: nil, now: now))
        #expect(!BalanceAlert.shouldFetch(lastAt: now.addingTimeInterval(-299), now: now))
        #expect(BalanceAlert.shouldFetch(lastAt: now.addingTimeInterval(-300), now: now))
    }

    @Test func alertToggleRejectsABlankAmount() async {
        let model = SettingsModelsModel(service: Script())
        #expect(!model.validateAlert(enabled: true, amount: " "))
        #expect(model.state.error == .invalidAmount)
        #expect(model.validateAlert(enabled: true, amount: "2"))
        model.state.balance = BalanceResponse(status: .ready, wallets: [Wallet(balance: "1.5")])
        #expect(model.showsBalanceNotice(enabled: true, amount: "2"))
        #expect(!model.showsBalanceNotice(enabled: false, amount: "2"))
    }

    @Test func balanceFetchWaitsFiveMinutes() async {
        let script = Script(balance: BalanceResponse(status: .ready, wallets: [Wallet(balance: "4")]))
        let model = SettingsModelsModel(service: script)
        let now = Date(timeIntervalSince1970: 1_000)
        await model.load(locale: "en", now: now, lastBalanceAt: nil)
        await model.load(locale: "en", now: now.addingTimeInterval(299), lastBalanceAt: model.state.lastBalanceAt)
        #expect(await script.balanceCalls == 1)
        #expect(model.state.balance.wallets?.first?.balance == "4")
        await model.load(locale: "en", now: now.addingTimeInterval(300), lastBalanceAt: model.state.lastBalanceAt)
        #expect(await script.balanceCalls == 2)
    }
}

private struct Update: Equatable {
    var ns: String
    var patch: [String: String]
    var revision: Int?
    init(_ ns: String, _ patch: [String: String], _ revision: Int?) {
        self.ns = ns
        self.patch = patch
        self.revision = revision
    }
}

private actor Script: SettingsModelsServing {
    var settingsView: MobileSettingsView
    var presetList: [AgentPreset]
    var modelList: SessionModelsResponse
    var balanceValue: BalanceResponse
    var providerValue: ProvidersResponse
    var updateValue: MobileSettingsNamespace
    var updateError: SettingsModelsError?
    var discoveredValue: DiscoverModelsResponse
    var writtenValue: ProvidersResponse
    var updates: [Update] = []
    var credentials: [(String, String)] = []
    var added: [(String, String?)] = []
    var modelWrites: [(String, [String], [String])] = []
    var balanceCalls = 0

    init(
        settings: MobileSettingsView = MobileSettingsView(),
        presets: [AgentPreset] = [],
        models: SessionModelsResponse = SessionModelsResponse(),
        balance: BalanceResponse = BalanceResponse(),
        providers: ProvidersResponse = ProvidersResponse(),
        update: MobileSettingsNamespace = MobileSettingsNamespace(),
        updateError: SettingsModelsError? = nil,
        discovered: DiscoverModelsResponse = DiscoverModelsResponse(),
        written: ProvidersResponse = ProvidersResponse()
    ) {
        settingsView = settings
        presetList = presets
        modelList = models
        balanceValue = balance
        providerValue = providers
        updateValue = update
        self.updateError = updateError
        discoveredValue = discovered
        writtenValue = written
    }

    func settings() async throws -> MobileSettingsView { settingsView }
    func presets() async throws -> [AgentPreset] { presetList }
    func models() async throws -> SessionModelsResponse { modelList }
    func balance(locale: String) async throws -> BalanceResponse {
        _ = locale
        balanceCalls += 1
        return balanceValue
    }
    func providers() async throws -> ProvidersResponse { providerValue }

    func update(ns: String, patch: [String: String], expectedRevision: Int?) async throws -> MobileSettingsNamespace {
        updates.append(Update(ns, patch, expectedRevision))
        if let updateError { throw updateError }
        return updateValue
    }

    func addProvider(id: String, apiKey: String?) async throws -> ProvidersResponse {
        added.append((id, apiKey))
        return writtenValue
    }

    func replaceCredential(provider: String, apiKey: String) async throws -> ProvidersResponse {
        credentials.append((provider, apiKey))
        return writtenValue
    }

    func discover(provider: String) async throws -> DiscoverModelsResponse {
        _ = provider
        return discoveredValue
    }

    func saveModels(provider: String, add: [DiscoveredModel], remove: [String]) async throws -> ProvidersResponse {
        modelWrites.append((provider, add.compactMap(\.id), remove))
        return writtenValue
    }
}

private func view(writable: Bool, namespaces: [MobileSettingsNamespace] = []) -> MobileSettingsView {
    MobileSettingsView(writable: writable, namespaces: namespaces)
}

private func namespace(_ ns: String, _ values: [String: String], revision: Int) -> MobileSettingsNamespace {
    MobileSettingsNamespace(
        ns: ns, value: .object(values.mapValues { .string($0) }), revision: revision)
}

private func group(_ provider: String, _ id: String) -> SessionModelGroup {
    SessionModelGroup(
        provider: provider, providerName: provider,
        models: [SessionModelOption(id: id, reasoningEfforts: ["high"], defaultEffort: "high")])
}

private func directory(writable: Bool) -> ProvidersResponse {
    ProvidersResponse(writable: writable, providers: [], addable: [])
}
