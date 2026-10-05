import SwiftUI

enum SettingsPage: Hashable {
    case language
    case notifications
    case appearance
    case defaults
    case models
    case history
    case about
}

struct SettingsHomePage: View {
    var computerName: String
    var computerAddress: String
    var online = true
    @Environment(\.locale) private var locale

    var body: some View {
        let copy = SettingsCopy(locale: locale)
        Form {
            Section {
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
            }
        }
        .navigationTitle(copy.text(.title))
        .navigationDestination(for: SettingsPage.self) { page in
            SettingsDetailPage(page: page)
        }
    }
}

struct SettingsDetailPage: View {
    var page: SettingsPage
    @AppStorage("settings.theme") private var theme = "system"
    @AppStorage("settings.notifyApproval") private var notifyApproval = false
    @AppStorage("settings.notifyDone") private var notifyDone = false
    @AppStorage("settings.liveActivity") private var liveActivity = false
    @Environment(\.locale) private var locale

    var body: some View {
        let copy = SettingsCopy(locale: locale)
        Form {
            switch page {
            case .language:
                LabeledContent(copy.text(.language), value: copy.text(.languageValue))
            case .notifications:
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
                Text(copy.text(.modelsEmpty))
                    .foregroundStyle(.secondary)
            case .history:
                Text(copy.text(.historyEmpty))
                    .foregroundStyle(.secondary)
            case .about:
                LabeledContent(copy.text(.version), value: copy.text(.versionValue))
                Text(copy.text(.licenses))
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle(title(copy))
        .navigationBarTitleDisplayMode(.inline)
    }

    private func title(_ copy: SettingsCopy) -> String {
        switch page {
        case .language: copy.text(.language)
        case .notifications: copy.text(.notifications)
        case .appearance: copy.text(.appearance)
        case .defaults: copy.text(.defaults)
        case .models: copy.text(.models)
        case .history: copy.text(.history)
        case .about: copy.text(.about)
        }
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
        }
    }
}

struct SettingsCopy {
    var locale: Locale

    func text(_ key: SettingsText) -> String {
        L10n.string("settings.\(key.rawValue)", fallback: key.fallback, locale: locale)
    }
}
