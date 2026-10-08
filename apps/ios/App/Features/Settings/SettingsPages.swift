import DLCore
import DLModels
import DLSecurity
import SwiftUI

#if canImport(UIKit)
    import UIKit
#endif

/// 设置页要显示的连接路线。与首页 `InboxRouteKind` 一一对应，
/// 但设置页属于纯 UI 层，不依赖 Home 的类型。
enum SettingsRouteKind: Equatable, Sendable {
    case local
    case remote
}

enum SettingsPage: Hashable {
    case computer
    case diagnostics
    case language
    case notifications
    case appearance
    case defaults
    case models
    case history
    case about
    case legal
    case crash
}

struct SettingsHomePage: View {
    var computerName: String
    var computerAddress: String
    var online = true
    /// 本次实际走通的路线（局域网直连 / 远程）。nil = 尚未连上。
    /// C10 要求 3：局域网/远程/离线必须来自**同一状态源**，不能各行其是。
    var route: SettingsRouteKind?
    var account: (any SettingsAccountServing)?
    var crashReport: SettingsCrashReport?
    var models: SettingsModelsModel?
    var push: PushSettingsRegistration?
    @Environment(\.locale) private var locale

    var body: some View {
        let copy = SettingsCopy(locale: locale)
        Form {
            Section {
                NavigationLink(value: SettingsPage.computer) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(computerName).font(.headline)
                        Text(computerAddress)
                            .font(.footnote.monospaced())
                            .foregroundStyle(.secondary)
                        Text(online ? copy.text(.online) : copy.text(.offline))
                            .font(.footnote)
                            .foregroundStyle(online ? .green : .secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .frame(minHeight: 44)
                }
            }
            Section(copy.text(.general)) {
                NavigationLink(copy.text(.language), value: SettingsPage.language)
                NavigationLink(copy.text(.notifications), value: SettingsPage.notifications)
                NavigationLink(copy.text(.appearance), value: SettingsPage.appearance)
            }
            Section(copy.text(.agent)) {
                NavigationLink(copy.text(.defaults), value: SettingsPage.defaults)
                NavigationLink(copy.text(.models), value: SettingsPage.models)
            }
            Section(copy.text(.other)) {
                NavigationLink(copy.text(.history), value: SettingsPage.history)
                NavigationLink(copy.text(.about), value: SettingsPage.about)
                NavigationLink(copy.text(.crash), value: SettingsPage.crash)
            }
        }
        .navigationTitle(copy.text(.title))
        .navigationDestination(for: SettingsPage.self) { page in
            SettingsDetailPage(
                page: page, route: route, account: account, crashReport: crashReport, models: models,
                push: push)
        }
        .task {
            if let models {
                await models.load(
                    locale: locale.identifier, lastBalanceAt: models.state.lastBalanceAt)
            }
        }
    }
}

struct SettingsDetailPage: View {
    var page: SettingsPage
    /// 本次实际走通的路线；由 `SettingsHomePage` 传入，与首页同一状态源（C10 要求 3）。
    var route: SettingsRouteKind?
    /// 构建元数据可注入：截图基线必须固定取值，否则「关于」页每次提交/每天都会变，
    /// 基线永远追不上（与 Android 关于页同一处理）。
    var buildInfo: BuildInfo = .from()
    var account: (any SettingsAccountServing)?
    var crashReport: SettingsCrashReport?
    var models: SettingsModelsModel?
    var push: PushSettingsRegistration?
    @AppStorage("settings.theme") private var theme = "system"
    @AppStorage("settings.notifyMaster") private var notifyMaster = false
    @AppStorage("settings.notifyApproval") private var notifyApproval = false
    @AppStorage("settings.notifyQuestion") private var notifyQuestion = false
    @AppStorage("settings.notifyDone") private var notifyDone = false
    @AppStorage("settings.notifyFailed") private var notifyFailed = false
    @AppStorage("settings.liveActivity") private var liveActivity = false
    @AppStorage("settings.balanceAlert") private var balanceAlert = false
    @AppStorage("settings.balanceAlertAmount") private var balanceAlertAmount = ""
    @State private var apiKey = ""
    @State private var computerName = ""
    @State private var loadedChecks: [DiagnosticCheck]?
    @State private var notice: String?
    @State private var busy = false
    @State private var unpaired = false
    @State private var crashExport: SettingsCrashExport?
    @State private var selectedDiscovered: Set<String> = []
    @State private var presetID: String?
    @State private var pushAvailable = false
    @State private var pushNotice: String?
    @State private var modelID = ""
    @State private var effortID: String?
    @Environment(\.locale) private var locale

    var body: some View {
        let copy = SettingsCopy(locale: locale)
        Form {
            switch page {
            case .computer:
                // C10 要求 3：三条路线显示**真实**状态。以前三条里两条写死
                // 「未连接」、剩下一条写死「已连接」，与实际走哪条路无关。
                LabeledContent(
                    copy.text(.lan),
                    value: copy.text(route == .local ? .online : .notPaired))
                LabeledContent(
                    copy.text(.tailscale),
                    value: copy.text(.notPaired))
                LabeledContent(
                    copy.text(.relay),
                    value: copy.text(route == .remote ? .online : .notPaired))
                NavigationLink(copy.text(.diagnostics), value: SettingsPage.diagnostics)
                if account != nil {
                    TextField(copy.text(.renameComputer), text: $computerName)
                    Button(copy.text(.renameComputer)) { Task { await renameComputer(copy) } }
                        .disabled(busy || computerName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    Button(copy.text(.replaceComputer)) {}
                    Button(copy.text(.unpair), role: .destructive) { Task { await unpair(copy) } }
                        .disabled(busy || unpaired)
                    if let notice {
                        Text(notice)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                } else {
                    Button(copy.text(.renameComputer)) {}
                    Button(copy.text(.replaceComputer)) {}
                    Button(copy.text(.unpair), role: .destructive) {}
                }
            case .diagnostics:
                ForEach(displayedChecks, id: \.id) { check in
                    Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(check.id ?? "")
                            Text(check.code ?? "")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: diagnosticSymbol(check.status))
                    }
                    .frame(minHeight: 44)
                }
                // C10 要求 2：没有真实结果时**明确说明**，不留空白，也不用样例填充。
                if displayedChecks.isEmpty {
                    Text(copy.text(.diagnosticsEmpty))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            case .language:
                LabeledContent(copy.text(.language), value: copy.text(.languageValue))
            case .notifications:
                Toggle(copy.text(.notifyMaster), isOn: notifyMasterBinding)
                    .disabled(!pushAvailable)
                if !pushAvailable {
                    Text(copy.text(.pushUnavailable))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                if let pushNotice {
                    Text(pushNotice)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Toggle(copy.text(.notifyApproval), isOn: $notifyApproval)
                    .disabled(!notifyMaster)
                Toggle(copy.text(.notifyQuestion), isOn: $notifyQuestion)
                    .disabled(!notifyMaster)
                Toggle(copy.text(.notifyDone), isOn: $notifyDone)
                    .disabled(!notifyMaster)
                Toggle(copy.text(.notifyFailed), isOn: $notifyFailed)
                    .disabled(!notifyMaster || !pushAvailable)
                Text(copy.text(.pushPrivacy))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            case .appearance:
                Picker(copy.text(.appearance), selection: $theme) {
                    Text(copy.text(.themeSystem)).tag("system")
                    Text(copy.text(.themeLight)).tag("light")
                    Text(copy.text(.themeDark)).tag("dark")
                }
                .pickerStyle(.inline)
                Text(copy.text(.typeSizeNote))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            case .defaults:
                defaultsSection(copy)
            case .models:
                modelsSection(copy)
            case .history:
                Text(copy.text(.historyEmpty))
                    .foregroundStyle(.secondary)
            case .about:
                LabeledContent(copy.text(.version), value: buildInfo.versionLine)
                LabeledContent(copy.text(.buildCommit), value: buildInfo.commit)
                LabeledContent(copy.text(.buildDate), value: buildInfo.date)
                LabeledContent(copy.text(.buildConfiguration), value: buildInfo.configuration)
                LabeledContent(copy.text(.buildContractVersion), value: buildInfo.contractVersion)
                NavigationLink(copy.text(.legal), value: SettingsPage.legal)
                Text(copy.text(.licenses))
                    .foregroundStyle(.secondary)
            case .legal:
                Text(copy.text(.legalBody))
                    .font(.body)
            case .crash:
                Text(copy.text(.crashBody))
                    .font(.footnote)
                if let crashExport {
                    ShareLink(item: crashExport.text, preview: SharePreview(copy.text(.crash))) {
                        Label(copy.text(.exportCrash), systemImage: "square.and.arrow.up")
                    }
                } else if crashReport != nil {
                    Button(copy.text(.exportCrash)) { exportCrash(copy) }
                } else {
                    Button(copy.text(.exportCrash)) {}
                }
            }
        }
        .navigationTitle(title(copy))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await loadAccount(copy)
            await refreshPush(copy)
        }
        .onChange(of: notifyApproval) { _, _ in Task { await refreshPush(copy) } }
        .onChange(of: notifyQuestion) { _, _ in Task { await refreshPush(copy) } }
        .onChange(of: notifyDone) { _, _ in Task { await refreshPush(copy) } }
        .onChange(of: notifyFailed) { _, _ in Task { await refreshPush(copy) } }
        // RFC 0002 §16.2.1: a rotated APNs token must re-register immediately,
        // not only on the next cold launch.
        .onReceive(NotificationCenter.default.publisher(for: .deepLinksPushTokenChanged)) { _ in
            Task { await refreshPush(copy) }
        }
        .toolbar {
            if page == .diagnostics {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(copy.text(.copyResult)) { copyDiagnostics() }
                }
            }
        }
        .onAppear { syncDrafts() }
        .onChange(of: models?.state.defaults) { _, _ in syncDrafts() }
    }

    private func syncDrafts() {
        guard let defaults = models?.state.defaults else { return }
        presetID = defaults.agentPreset
        modelID = [defaults.modelProvider, defaults.model].compactMap { $0 }.joined(separator: "\n")
        effortID = defaults.reasoningEffort
    }

    /// 诊断页真正要显示的检查项。
    ///
    /// C10 要求 2：**生产默认不得使用样例诊断**。以前这里在未加载时回退到样例，
    /// 于是「查询失败」的提示旁边会同时列出伪造的 OK/WARN 结果 —— 用户会真的相信
    /// 那三条是这台电脑的诊断结论。现在只有两种状态：真实结果，或者空（由 UI 说明）。
    private var displayedChecks: [DiagnosticCheck] {
        loadedChecks ?? []
    }

    private func loadAccount(_ copy: SettingsCopy) async {
        guard let account else { return }
        if page == .computer {
            let snapshot = await account.loadComputer()
            if computerName.isEmpty { computerName = snapshot.displayName }
        }
        if page == .diagnostics {
            do {
                let report = try await account.diagnostics()
                loadedChecks = report.checks ?? []
                notice = nil
            } catch {
                // 明确置空，避免回退到样例（见 displayedChecks 注释）。
                loadedChecks = []
                notice = copy.text(.diagnosticsUnavailable)
            }
        }
    }

    private var notifyMasterBinding: Binding<Bool> {
        Binding(
            get: { notifyMaster },
            set: { value in
                notifyMaster = value
                Task { await refreshPush(SettingsCopy(locale: locale)) }
            })
    }

    private func refreshPush(_ copy: SettingsCopy) async {
        guard page == .notifications else { return }
        let capabilities = LocalPushCapabilities(
            version: push?.pushVersion ?? 0, apnsBuildEnabled: APNsBuild.enabled)
        pushAvailable = capabilities.canEnable
        // A free-signed build has no aps-environment. Do not request a token or
        // report registration until both the host capability and that entitlement exist.
        guard capabilities.canEnable, let service = push?.service() else {
            notifyMaster = false
            pushNotice = copy.text(.pushUnavailable)
            return
        }
        let outcome = await service.sync(
            enabled: notifyMaster,
            preferences: PushPreferences(
                approval: notifyApproval, question: notifyQuestion, completed: notifyDone, failed: notifyFailed))
        apply(outcome, copy: copy)
    }

    private func apply(_ outcome: PushRegistrationOutcome, copy: SettingsCopy) {
        switch outcome {
        case .registered:
            pushNotice = nil
        case .disabled:
            notifyMaster = false
            pushNotice = nil
        case .unavailable:
            notifyMaster = false
            pushAvailable = false
            pushNotice = copy.text(.pushUnavailable)
        case .failed:
            notifyMaster = false
            pushNotice = copy.text(.pushFailed)
        }
    }

    private func renameComputer(_ copy: SettingsCopy) async {
        guard let account, !busy else { return }
        busy = true
        defer { busy = false }
        if let renamed = await account.renameComputer(computerName) {
            computerName = renamed.displayName
            notice = nil
        } else {
            notice = copy.text(.renameFailed)
        }
    }

    private func unpair(_ copy: SettingsCopy) async {
        guard let account, !busy else { return }
        busy = true
        defer { busy = false }
        let outcome = await account.unpair()
        if shouldDeleteCredentials(after: outcome) {
            unpaired = true
            notice = nil
            notifyMaster = false
            Task { _ = await push?.service().unregister() }
        } else {
            notice = copy.text(.unpairFailed)
        }
    }

    private func exportCrash(_ copy: SettingsCopy) {
        _ = copy
        crashExport = SettingsCrashStore.export(from: crashReport)
    }

    private func copyDiagnostics() {
        #if canImport(UIKit)
            UIPasteboard.general.string = diagnosticsClipboard(displayedChecks)
        #endif
    }

    @ViewBuilder private func defaultsSection(_ copy: SettingsCopy) -> some View {
        if let models {
            Picker(copy.text(.preset), selection: $presetID) {
                if presetID == nil { Text(copy.text(.modelValue)).tag(Optional<String>.none) }
                ForEach(models.state.presets) { preset in
                    Text(preset.name ?? preset.id).tag(Optional(preset.id))
                }
            }
            .disabled(!models.state.settingsWritable || models.state.presets.isEmpty)
            .onChange(of: presetID) { _, value in
                guard let value, value != models.state.defaults.agentPreset else { return }
                Task { await models.savePreset(value) }
            }
            LabeledContent(
                copy.text(.permission),
                value: models.state.defaults.permissionPreset ?? copy.text(.modelValue))
            Picker(copy.text(.model), selection: $modelID) {
                Text(copy.text(.modelValue)).tag("")
                ForEach(modelChoices(models.state.modelGroups)) { choice in
                    Text(choice.title).tag(choice.id)
                }
            }
            .disabled(!models.state.settingsWritable || models.state.modelGroups.isEmpty)
            .onChange(of: modelID) { _, value in
                let current = [models.state.defaults.modelProvider, models.state.defaults.model].compactMap { $0 }
                    .joined(separator: "\n")
                let parts = value.split(separator: "\n", maxSplits: 1).map(String.init)
                guard parts.count == 2, value != current else { return }
                Task { await models.saveDefaultModel(provider: parts[0], model: parts[1]) }
            }
            Picker(copy.text(.reasoningEffort), selection: $effortID) {
                Text(copy.text(.modelValue)).tag(Optional<String>.none)
                ForEach(effortChoices(models.state), id: \.self) { effort in
                    Text(effort).tag(Optional(effort))
                }
            }
            .disabled(!models.state.settingsWritable || effortChoices(models.state).isEmpty)
            .onChange(of: effortID) { _, value in
                guard let value, value != models.state.defaults.reasoningEffort else { return }
                Task { await models.saveReasoningEffort(value) }
            }
            LabeledContent(
                copy.text(.busySend),
                value: models.state.defaults.busyEnter ?? copy.text(.modelValue))
            if let error = models.state.error {
                Text(errorText(error, copy: copy)).font(.footnote).foregroundStyle(.red)
            }
        } else {
            LabeledContent(copy.text(.preset), value: copy.text(.presetValue))
            LabeledContent(copy.text(.permission), value: copy.text(.permissionValue))
            LabeledContent(copy.text(.model), value: copy.text(.modelValue))
            LabeledContent(copy.text(.busySend), value: copy.text(.busyQueue))
        }
    }

    @ViewBuilder private func modelsSection(_ copy: SettingsCopy) -> some View {
        if let models {
            LabeledContent(copy.text(.balance), value: balanceText(models.state.balance, copy: copy))
            if models.showsBalanceNotice(enabled: balanceAlert, amount: balanceAlertAmount) {
                Text(copy.text(.balanceAlert)).font(.footnote).foregroundStyle(.orange)
            }
            ForEach(models.state.providers.providers ?? [], id: \.provider) { row in
                providerRow(row, copy: copy, models: models)
            }
            if let addable = models.state.providers.addable, !addable.isEmpty, models.state.providers.writable == true {
                Picker(
                    copy.text(.providers),
                    selection: Binding(
                        get: { "" },
                        set: { id in
                            guard !id.isEmpty else { return }
                            Task { await models.addProvider(id: id) }
                        })
                ) {
                    Text(copy.text(.providersValue)).tag("")
                    ForEach(addable, id: \.provider) { item in
                        Text(item.displayName ?? item.provider ?? "").tag(item.provider ?? "")
                    }
                }
            } else if (models.state.providers.providers ?? []).isEmpty {
                LabeledContent(copy.text(.providers), value: copy.text(.providersValue))
            }
            Text(copy.text(.customProviderDesktop)).font(.footnote).foregroundStyle(.secondary)
            SecureField(copy.text(.apiKey), text: keyBinding(models))
                .textContentType(.password)
            Toggle(copy.text(.balanceAlert), isOn: $balanceAlert)
            if balanceAlert {
                TextField(copy.text(.balanceAlertAmount), text: $balanceAlertAmount)
                    .onSubmit { _ = models.validateAlert(enabled: true, amount: balanceAlertAmount) }
            }
            if let error = models.state.error {
                Text(errorText(error, copy: copy)).font(.footnote).foregroundStyle(.red)
            }
        } else {
            LabeledContent(copy.text(.balance), value: copy.text(.balanceValue))
            LabeledContent(copy.text(.providers), value: copy.text(.providersValue))
            SecureField(copy.text(.apiKey), text: $apiKey).textContentType(.password)
            Button(copy.text(.discoverModels)) {}
            Toggle(copy.text(.balanceAlert), isOn: $balanceAlert)
        }
    }

    @ViewBuilder private func providerRow(_ row: ProviderRow, copy: SettingsCopy, models: SettingsModelsModel)
        -> some View
    {
        let id = row.provider ?? ""
        LabeledContent(row.displayName ?? id, value: (row.models ?? []).compactMap(\.id).joined(separator: ", "))
        if row.credential?.writable == true {
            Button(copy.text(.apiKey)) { Task { await models.replaceCredential(provider: id) } }
        }
        if row.canDiscover == true {
            Button(copy.text(.discoverModels)) {
                selectedDiscovered = []
                Task { await models.discover(provider: id) }
            }
        }
        if models.state.discovered.isEmpty == false, row.canDiscover == true {
            ForEach(models.state.discovered, id: \.id) { model in
                let modelID = model.id ?? ""
                Toggle(
                    model.name ?? modelID,
                    isOn: Binding(
                        get: { selectedDiscovered.contains(modelID) },
                        set: { on in
                            if on { selectedDiscovered.insert(modelID) } else { selectedDiscovered.remove(modelID) }
                        }))
            }
            Button(copy.text(.saveModels)) {
                Task { await models.saveDiscovered(provider: id, selected: selectedDiscovered) }
            }
            .disabled(row.modelsEditable != true)
        }
    }

    private func keyBinding(_ models: SettingsModelsModel) -> Binding<String> {
        Binding(get: { models.state.apiKey }, set: { models.state.apiKey = $0 })
    }

    private func effortChoices(_ state: SettingsModelsState) -> [String] {
        guard let provider = state.defaults.modelProvider, let model = state.defaults.model else { return [] }
        return state.modelGroups.first { $0.provider == provider }?.models?.first { $0.id == model }?.reasoningEfforts
            ?? []
    }

    private func modelChoices(_ groups: [SessionModelGroup]) -> [SettingsModelChoice] {
        groups.flatMap { group in
            (group.models ?? []).map { model in
                SettingsModelChoice(
                    id: [group.provider, model.id].compactMap { $0 }.joined(separator: "\n"),
                    title: [group.providerName ?? group.provider, model.name ?? model.id].compactMap { $0 }
                        .joined(separator: " "))
            }
        }
    }

    private func balanceText(_ balance: BalanceResponse, copy: SettingsCopy) -> String {
        switch balance.status {
        case .ready:
            (balance.wallets ?? []).compactMap { wallet in
                [wallet.currency, wallet.balance].compactMap { $0 }.joined(separator: " ")
            }.joined(separator: ", ")
        case .signedOut: copy.text(.balanceSignedOut)
        case .failed: copy.text(.balanceFailed)
        case .unavailable, .none, .unknown: copy.text(.balanceValue)
        }
    }

    private func errorText(_ error: SettingsModelsError, copy: SettingsCopy) -> String {
        switch error {
        case .offline: copy.text(.errorOffline)
        case .missingHost: copy.text(.errorMissingHost)
        case .unauthorized: copy.text(.errorUnauthorized)
        case .certificate: copy.text(.errorCertificate)
        case .notWritable: copy.text(.errorReadOnly)
        case .conflict: copy.text(.errorConflict)
        case .rejected: copy.text(.errorRejected)
        case .emptyKey: copy.text(.errorEmptyKey)
        case .invalidKey: copy.text(.errorInvalidKey)
        case .invalidAmount: copy.text(.errorInvalidAmount)
        case .notAddable: copy.text(.errorNotAddable)
        case .modelsInherited: copy.text(.errorModelsInherited)
        case .emptyModels: copy.text(.errorEmptyModels)
        }
    }

    private func title(_ copy: SettingsCopy) -> String {
        switch page {
        case .computer: copy.text(.computer)
        case .diagnostics: copy.text(.diagnostics)
        case .language: copy.text(.language)
        case .notifications: copy.text(.notifications)
        case .appearance: copy.text(.appearance)
        case .defaults: copy.text(.defaults)
        case .models: copy.text(.models)
        case .history: copy.text(.history)
        case .about: copy.text(.about)
        case .legal: copy.text(.legal)
        case .crash: copy.text(.crash)
        }
    }
}

func diagnosticSymbol(_ status: DiagnosticsStatus?) -> String {
    switch status {
    case .ok: "checkmark.circle"
    case .warn: "exclamationmark.triangle"
    case .fail: "xmark.circle"
    case .skip: "minus.circle"
    default: "questionmark.circle"
    }
}

func diagnosticsClipboard(_ checks: [DiagnosticCheck]) -> String {
    checks.map { check in
        let detail = (check.detail ?? [:])
            .sorted { $0.key < $1.key }
            .map { key, value in "\(key)=\(diagnosticDetailText(value))" }
            .joined(separator: ",")
        let tail = detail.isEmpty ? "" : " \(detail)"
        return "\(check.id ?? "") \(check.status?.encodedValue ?? "") \(check.code ?? "")\(tail)"
    }
    .joined(separator: "\n")
}

/// 诊断导出里**唯一**允许出现的明细形状：数字、布尔、短枚举码。
///
/// C16 §20.3 要求导出「不带 token、二维码凭据、API key、正文或完整敏感路径」。
/// 插件侧在生成报告时用 `PRIVATE_TEXT`（`src/diagnostics.js:361`）强制拦截；
/// 手机侧此前把 `.text` 明细**原样**写进剪贴板 —— 上游一旦把 token 或路径当明细塞进来，
/// 就会随「复制诊断」一起泄露。
///
/// 这里两道一起用，缺一不可：
/// 1. **形状白名单**：短、只含 `A–Z a–z 0–9 . _ -`。挡路径、URL、正文、带空格的凭据。
/// 2. **敏感形状黑名单**：与插件同一组正则。挡 `ghp_…` 这类只含合法字符的长凭据，
///    以及 `10.255.255.1` 这种纯数字点的 IP —— 它们能过白名单，只有黑名单认得。
private enum DiagnosticDetailPrivacy {
    /// 允许的字符集：字母、数字、点、下划线、连字符。刻意**不含**空白与斜杠。
    private static let allowed = CharacterSet(
        charactersIn:
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-")

    /// 与插件 `src/diagnostics.js:361` 的 `PRIVATE_TEXT` 同一组规则，
    /// 另加常见凭据前缀（`ghp_`/`gho_`/`sk-` 等）与长十六进制串：
    /// 这些只含合法字符，白名单认不出，必须按形状拦。
    private static let sensitive = try? NSRegularExpression(
        pattern:
            #"[/\\]|token|secret|password|bearer|key|credential|\b(?:\d{1,3}\.){3}\d{1,3}\b|\d{16,}|\b(?:gh[pousr]_|sk-|xox[baprs]-)[A-Za-z0-9_-]{8,}"#,
        options: [.caseInsensitive])

    /// 明细值是否可安全导出。宁少不多：拒绝只是少一条明细，误放行会泄露。
    static func isSafe(_ text: String) -> Bool {
        // 枚举码很短；放宽到 64 只为容下较长的诊断码，仍远小于任何凭据/正文。
        guard !text.isEmpty, text.count <= 64 else { return false }
        guard text.unicodeScalars.allSatisfy({ allowed.contains($0) }) else { return false }
        guard let sensitive else { return false }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return sensitive.firstMatch(in: text, options: [], range: range) == nil
    }
}

private func diagnosticDetailText(_ value: DiagnosticDetailValue) -> String {
    switch value {
    case .number(let number): String(number)
    case .flag(let flag): flag ? "true" : "false"
    case .text(let text): DiagnosticDetailPrivacy.isSafe(text) ? text : "redacted"
    }
}

private struct SettingsModelChoice: Identifiable {
    var id: String
    var title: String
}

enum SettingsText: String {
    case title
    case online
    case offline
    case general
    case language
    case languageValue
    case notifications
    case appearance
    case agent
    case defaults
    case models
    case other
    case history
    case about
    case notifyApproval
    case notifyQuestion
    case notifyDone
    case notifyFailed
    case pushUnavailable
    case pushFailed
    case pushPrivacy
    case liveActivity
    case pushNote
    case themeSystem
    case themeLight
    case themeDark
    case typeSizeNote
    case preset
    case presetValue
    case permission
    case permissionValue
    case model
    case modelValue
    case reasoningEffort
    case busySend
    case busyQueue
    case balanceSignedOut
    case balanceFailed
    case balanceAlertAmount
    case saveModels
    case customProviderDesktop
    case modelsEmpty
    case historyEmpty
    case version
    case versionValue
    case buildCommit
    case buildDate
    case buildConfiguration
    case buildContractVersion
    case licenses
    case computer
    case lan
    case tailscale
    case relay
    case notPaired
    case diagnostics
    case renameComputer
    case replaceComputer
    case unpair
    case copyResult
    case notifyMaster
    case balance
    case balanceValue
    case providers
    case providersValue
    case apiKey
    case discoverModels
    case balanceAlert
    case legal
    case legalBody
    case crash
    case crashBody
    case exportCrash
    case renameFailed
    case unpairFailed
    case diagnosticsUnavailable
    /// C10 要求 2：没有真实诊断结果时的明确说明（不用样例填充）。
    case diagnosticsEmpty
    case errorOffline
    case errorMissingHost
    case errorUnauthorized
    case errorCertificate
    case errorReadOnly
    case errorConflict
    case errorRejected
    case errorEmptyKey
    case errorInvalidKey
    case errorInvalidAmount
    case errorNotAddable
    case errorModelsInherited
    case errorEmptyModels

    var fallback: String {
        switch self {
        case .title: "Settings"
        case .online: "Online"
        case .offline: "Offline"
        case .general: "General"
        case .language: "Language"
        case .languageValue: "Follows the system"
        case .notifications: "Notifications"
        case .appearance: "Appearance"
        case .agent: "Agent"
        case .defaults: "Conversation defaults"
        case .models: "Models and balance"
        case .other: "Other"
        case .history: "Session history"
        case .about: "About"
        case .notifyApproval: "When approval is needed"
        case .notifyQuestion: "When a question arrives"
        case .notifyDone: "When a task finishes"
        case .notifyFailed: "When a task fails"
        case .liveActivity: "Live Activity"
        case .pushUnavailable: "This build cannot register for notifications."
        case .pushFailed: "Notifications could not be registered."
        case .pushPrivacy:
            "Notifications pass through the gateway, but their content stays end-to-end encrypted. They can only open the app."
        case .pushNote: "Delivery through the official gateway arrives in a later update."
        case .themeSystem: "System"
        case .themeLight: "Light"
        case .themeDark: "Dark"
        case .typeSizeNote: "Text size follows the system setting."
        case .preset: "Agent preset"
        case .presetValue: "Default"
        case .permission: "Permission"
        case .permissionValue: "Workspace write"
        case .model: "Model"
        case .modelValue: "Not chosen"
        case .reasoningEffort: "Reasoning"
        case .busySend: "Send while running"
        case .busyQueue: "Queue"
        case .balanceSignedOut: "Signed out on this computer"
        case .balanceFailed: "Balance lookup failed"
        case .balanceAlertAmount: "Amount"
        case .saveModels: "Save models"
        case .customProviderDesktop: "Custom protocol and base URL stay on the computer."
        case .modelsEmpty: "Models and balance are read from this computer."
        case .historyEmpty: "No archived sessions on this phone."
        case .version: "Version"
        case .versionValue: "1.0"
        case .buildCommit: "Build"
        case .buildDate: "Built"
        case .buildConfiguration: "Configuration"
        case .buildContractVersion: "Contract"
        case .licenses: "Open source licenses"
        case .computer: "This computer"
        case .lan: "Local network"
        case .tailscale: "Tailscale"
        case .relay: "Relay"
        case .notPaired: "Not connected"
        case .diagnostics: "Connection diagnostics"
        case .renameComputer: "Rename"
        case .replaceComputer: "Replace computer"
        case .unpair: "Unpair"
        case .copyResult: "Copy result"
        case .notifyMaster: "Notifications"
        case .balance: "Balance"
        case .balanceValue: "Unavailable"
        case .providers: "Providers"
        case .providersValue: "None yet"
        case .apiKey: "API key"
        case .discoverModels: "Fetch models"
        case .balanceAlert: "Balance reminder"
        case .legal: "Legal"
        case .legalBody: "Privacy and terms stay on this phone until you export them."
        case .crash: "Last crash"
        case .crashBody: "MetricKit keeps the last diagnostic on this phone. Nothing is uploaded."
        case .exportCrash: "Export"
        case .renameFailed: "Couldn't rename this computer."
        case .unpairFailed: "Couldn't unpair. The saved credential was kept."
        case .diagnosticsUnavailable: "Diagnostics are unavailable on this computer."
        case .diagnosticsEmpty: "No diagnostics yet. Connect to your computer and try again."
        case .errorOffline: "This computer is offline."
        case .errorMissingHost: "This computer is not paired."
        case .errorUnauthorized: "Sign in again on this computer."
        case .errorCertificate: "The computer certificate does not match."
        case .errorReadOnly: "This setting is read only."
        case .errorConflict: "This setting changed on the computer. Reload it."
        case .errorRejected: "The computer rejected this change."
        case .errorEmptyKey: "Enter an API key before saving."
        case .errorInvalidKey: "This API key is not accepted."
        case .errorInvalidAmount: "Enter an amount of zero or more."
        case .errorNotAddable: "This provider cannot be added from the phone."
        case .errorModelsInherited: "These models are inherited and cannot be edited here."
        case .errorEmptyModels: "Keep at least one model."
        }
    }
}

struct SettingsCopy {
    var locale: Locale

    func text(_ key: SettingsText) -> String {
        L10n.string("settings.\(key.rawValue)", fallback: key.fallback, locale: locale)
    }
}
