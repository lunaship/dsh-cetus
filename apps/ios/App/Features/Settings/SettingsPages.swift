import DLModels
import SwiftUI

#if canImport(UIKit)
    import UIKit
#endif

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
    var account: (any SettingsAccountServing)?
    var crashReport: SettingsCrashReport?
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
            SettingsDetailPage(page: page, account: account, crashReport: crashReport)
        }
    }
}

struct SettingsDetailPage: View {
    var page: SettingsPage
    var checks: [DiagnosticCheck] = SettingsDetailPage.sampleChecks
    var account: (any SettingsAccountServing)?
    var crashReport: SettingsCrashReport?
    @AppStorage("settings.theme") private var theme = "system"
    @AppStorage("settings.notifyMaster") private var notifyMaster = false
    @AppStorage("settings.notifyApproval") private var notifyApproval = false
    @AppStorage("settings.notifyDone") private var notifyDone = false
    @AppStorage("settings.liveActivity") private var liveActivity = false
    @AppStorage("settings.balanceAlert") private var balanceAlert = false
    @State private var apiKey = ""
    @State private var computerName = ""
    @State private var loadedChecks: [DiagnosticCheck]?
    @State private var notice: String?
    @State private var busy = false
    @State private var unpaired = false
    @State private var crashExport: SettingsCrashExport?
    @Environment(\.locale) private var locale

    var body: some View {
        let copy = SettingsCopy(locale: locale)
        Form {
            switch page {
            case .computer:
                LabeledContent(copy.text(.lan), value: copy.text(.online))
                LabeledContent(copy.text(.tailscale), value: copy.text(.notPaired))
                LabeledContent(copy.text(.relay), value: copy.text(.notPaired))
                NavigationLink(copy.text(.diagnostics), value: SettingsPage.diagnostics)
                if account != nil {
                    TextField(copy.text(.renameComputer), text: $computerName)
                    Button(copy.text(.renameComputer)) { Task { await renameComputer(copy) } }
                        .disabled(busy || computerName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                } else {
                    Button(copy.text(.renameComputer)) {}
                }
                Button(copy.text(.replaceComputer)) {}
                Button(copy.text(.unpair), role: .destructive) { Task { await unpair(copy) } }
                    .disabled(busy || account == nil || unpaired)
                if let notice {
                    Text(notice)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
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
            case .language:
                LabeledContent(copy.text(.language), value: copy.text(.languageValue))
            case .notifications:
                Toggle(copy.text(.notifyMaster), isOn: $notifyMaster)
                Toggle(copy.text(.notifyApproval), isOn: $notifyApproval)
                Toggle(copy.text(.notifyDone), isOn: $notifyDone)
                Toggle(copy.text(.liveActivity), isOn: $liveActivity)
                Text(copy.text(.pushNote))
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
                LabeledContent(copy.text(.preset), value: copy.text(.presetValue))
                LabeledContent(copy.text(.permission), value: copy.text(.permissionValue))
                LabeledContent(copy.text(.model), value: copy.text(.modelValue))
                LabeledContent(copy.text(.busySend), value: copy.text(.busyQueue))
            case .models:
                LabeledContent(copy.text(.balance), value: copy.text(.balanceValue))
                LabeledContent(copy.text(.providers), value: copy.text(.providersValue))
                SecureField(copy.text(.apiKey), text: $apiKey)
                    .textContentType(.password)
                Button(copy.text(.discoverModels)) {}
                Toggle(copy.text(.balanceAlert), isOn: $balanceAlert)
            case .history:
                Text(copy.text(.historyEmpty))
                    .foregroundStyle(.secondary)
            case .about:
                LabeledContent(copy.text(.version), value: copy.text(.versionValue))
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
                } else {
                    Button(copy.text(.exportCrash)) { exportCrash(copy) }
                        .disabled(crashReport == nil)
                }
            }
        }
        .navigationTitle(title(copy))
        .navigationBarTitleDisplayMode(.inline)
        .task { await loadAccount(copy) }
        .toolbar {
            if page == .diagnostics {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(copy.text(.copyResult)) { copyDiagnostics() }
                }
            }
        }
    }

    private var displayedChecks: [DiagnosticCheck] {
        loadedChecks ?? checks
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
                notice = copy.text(.diagnosticsUnavailable)
            }
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

    static let sampleChecks = [
        DiagnosticCheck(id: "host.rpc", status: .ok, code: "HOST_RPC_OK"),
        DiagnosticCheck(id: "tls.cert", status: .warn, code: "TLS_CERT_EXPIRING"),
        DiagnosticCheck(id: "remote.relay", status: .skip, code: "REMOTE_DISABLED"),
    ]

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

private func diagnosticDetailText(_ value: DiagnosticDetailValue) -> String {
    switch value {
    case .number(let number): String(number)
    case .flag(let flag): flag ? "true" : "false"
    case .text(let text): text
    }
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
    case notifyDone
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
    case busySend
    case busyQueue
    case modelsEmpty
    case historyEmpty
    case version
    case versionValue
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
        case .notifyDone: "When a task finishes"
        case .liveActivity: "Live Activity"
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
        case .busySend: "Send while running"
        case .busyQueue: "Queue"
        case .modelsEmpty: "Models and balance are read from this computer."
        case .historyEmpty: "No archived sessions on this phone."
        case .version: "Version"
        case .versionValue: "1.0"
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
        }
    }
}

struct SettingsCopy {
    var locale: Locale

    func text(_ key: SettingsText) -> String {
        L10n.string("settings.\(key.rawValue)", fallback: key.fallback, locale: locale)
    }
}
