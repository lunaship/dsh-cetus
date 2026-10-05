import DLCore
import DLModels
import DLUI
import SwiftUI
import UIKit

enum ChatSurface: String, Identifiable {
    case palette
    case model
    case permission
    case confirmFull
    case attach
    case usage
    case agents
    case share
    case rename
    case delete
    case schedule
    case goal
    case selectText

    var id: String { rawValue }
}

struct ChatSurfaceScreen: View {
    var surface: ChatSurface
    var copy: ConversationCopy
    var draft = "/p"
    var models: [ModelRow] = ChatSurfaceScreen.sampleModels
    var selectedModel = "step-5"
    var effort = "medium"
    var permission: PermissionPreset = .workspaceWrite
    var usage: UsageFigures? = ChatSurfaceScreen.sampleUsage
    var agents: [SubagentNode] = ChatSurfaceScreen.sampleAgents
    var shareTitle = "登录超时"
    var shareBody = "助手: 我先看事件订阅。"
    var renameText = "登录超时"
    var schedules: [ScheduleTask] = ChatSurfaceScreen.sampleSchedules
    var goalText = "把批准状态对齐"
    var selectedText = "我先看事件订阅，再补过期回归。"
    var solid = true

    var body: some View {
        switch surface {
        case .palette:
            SlashPaletteScreen(draft: draft, copy: copy, solid: solid)
        case .model:
            ModelSheet(rows: models, selectedID: selectedModel, effort: effort, contextPercent: 46, copy: copy)
        case .permission:
            PermissionSheet(selected: permission, copy: copy, onSelect: { _ in })
        case .confirmFull:
            DialogCard(
                title: copy.text(.permConfirmTitle), message: copy.text(.permConfirmBody),
                dismiss: copy.text(.cancel), confirm: copy.text(.permEnable), destructive: true)
        case .attach:
            AttachSheet(copy: copy, cameraAvailable: true, onCamera: {}, onPhotos: {})
        case .usage:
            UsageSheet(figures: usage, copy: copy)
        case .agents:
            SubagentPage(nodes: agents, copy: copy, animated: !solid)
        case .share:
            ShareSheet(title: shareTitle, transcript: shareBody, copy: copy, plainActions: solid)
        case .rename:
            DialogCard(
                title: copy.text(.renameTitle), message: nil, field: renameText, dismiss: copy.text(.cancel),
                confirm: copy.text(.save), destructive: false)
        case .delete:
            DialogCard(
                title: copy.text(.deleteTitle), message: copy.text(.deleteBody), dismiss: copy.text(.cancel),
                confirm: copy.text(.deleteConfirm), destructive: true)
        case .schedule:
            SchedulePage(items: schedules, scope: .session, sessionID: "s1", copy: copy)
        case .goal:
            GoalEditSheet(text: goalText, rounds: 8, copy: copy, onClear: {})
        case .selectText:
            SelectTextPage(text: selectedText, copy: copy)
        }
    }

    static let sampleModels = [
        ModelRow(
            provider: "deepseek", providerName: "DeepSeek", id: "step-5", name: "Step 5", contextWindow: 128_000,
            efforts: ["low", "medium", "high"], defaultEffort: "medium"),
        ModelRow(
            provider: "deepseek", providerName: "DeepSeek", id: "step-5-mini", name: "Step 5 Mini",
            contextWindow: 64_000, efforts: ["low", "high"], defaultEffort: "low"),
    ]

    static let sampleUsage = UsageFigures(
        uncachedInputTokens: 1200, cacheReadTokens: 800, outputTokens: 400, totalTokens: 2400, cacheHitRate: 0.4,
        turns: 3, steps: 7, llmMs: 4200, toolMs: 800, avgTtftMs: 600, outputTokensPerSec: 18,
        contextUsedTokens: 46_000, contextWindowTokens: 100_000,
        breakdown: [UsageSlice(key: "messages", tokens: 25_000)])

    static let sampleAgents = [
        SubagentNode(id: "a", title: "查订阅者", running: true, depth: 0),
        SubagentNode(id: "b", title: "补测试", running: false, depth: 1),
    ]

    static let sampleSchedules = [
        ScheduleTask(id: "daily", sessionId: "s1", title: "早报", prompt: "汇总失败", kind: "daily", time: "09:00"),
    ]
}

struct SlashPaletteScreen: View {
    var draft: String
    var copy: ConversationCopy
    var solid: Bool

    var body: some View {
        VStack {
            Spacer(minLength: 0)
            ConversationBar(
                decision: nil, draft: draft, copy: copy, onDraft: { _ in }, onSend: {}, onSecondary: {},
                onPrimary: {}, solidSnapshot: solid, suggestions: slashSuggestions(draft: draft, copy: copy),
                onSuggestion: { _ in })
                .padding(.horizontal, 12)
                .padding(.bottom, 8)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(DLColor.background)
    }
}

func slashSuggestions(draft: String, copy: ConversationCopy) -> [ComposerSuggestion] {
    guard draft.hasPrefix("/") else { return [] }
    let entries = paletteEntries(
        title: { paletteCopy($0, copy: copy) }, detail: { paletteCopy($0, copy: copy) })
    return filterPalette(entries, query: draft).flatMap { section in
        section.items.map { item in
            ComposerSuggestion(
                id: item.command.trigger,
                group: paletteGroupTitle(section.group, copy: copy),
                title: item.command.title,
                detail: item.command.detail)
        }
    }
}

func paletteCopy(_ key: String, copy: ConversationCopy) -> String {
    guard let text = ChatText(rawValue: key) else { return key }
    return copy.text(text)
}

func paletteGroupTitle(_ group: PaletteGroup, copy: ConversationCopy) -> String {
    switch group {
    case .agent: copy.text(.paletteGroupAgent)
    case .session: copy.text(.paletteGroupSession)
    case .app: copy.text(.paletteGroupApp)
    }
}

struct ModelSheet: View {
    var rows: [ModelRow]
    var contextPercent: Int?
    var copy: ConversationCopy
    var onSelect: (ModelRow, String?) -> Void
    @State private var selectedID: String
    @State private var effort: String
    @State private var query = ""

    init(
        rows: [ModelRow], selectedID: String, effort: String, contextPercent: Int?, copy: ConversationCopy,
        onSelect: @escaping (ModelRow, String?) -> Void = { _, _ in }
    ) {
        self.rows = rows
        self.contextPercent = contextPercent
        self.copy = copy
        self.onSelect = onSelect
        _selectedID = State(initialValue: selectedID)
        _effort = State(initialValue: effort)
    }

    var body: some View {
        let shown = filterModelRows(rows, query: query)
        let current = rows.first { $0.id == selectedID }
        List {
            if showsModelSearch(rows.count) {
                TextField(copy.text(.searchModels), text: $query)
            }
            Section(copy.text(.modelSection)) {
                if shown.isEmpty {
                    Text(copy.text(.noModels)).foregroundStyle(DLColor.secondaryLabel)
                }
                ForEach(shown) { row in
                    Button {
                        selectedID = row.id
                        effort = row.efforts.contains(effort) ? effort : (row.defaultEffort ?? row.efforts.first ?? "")
                        onSelect(row, effort.isEmpty ? nil : effort)
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(row.name).font(DLFont.body).foregroundStyle(DLColor.label)
                                Text("\(row.providerName) · \(copy.compact(row.contextWindow ?? 0))")
                                    .font(DLFont.footnote)
                                    .foregroundStyle(DLColor.secondaryLabel)
                            }
                            Spacer()
                            if row.id == selectedID { Image(systemName: "checkmark") }
                        }
                        .frame(minHeight: 44)
                    }
                }
            }
            if let current, !current.efforts.isEmpty {
                Section(copy.text(.effortSection)) {
                    Picker(copy.text(.effortSection), selection: $effort) {
                        ForEach(current.efforts, id: \.self) { item in
                            Text(item).tag(item)
                        }
                    }
                    .pickerStyle(.segmented)
                    .onChange(of: effort) { _, new in
                        if let current { onSelect(current, new) }
                    }
                }
            }
            if let contextPercent {
                Text(copy.format(.contextUsed, contextPercent))
                    .font(DLFont.footnote)
                    .foregroundStyle(DLColor.secondaryLabel)
            } else {
                Text(copy.text(.modelScope))
                    .font(DLFont.footnote)
                    .foregroundStyle(DLColor.secondaryLabel)
            }
        }
        .navigationTitle(copy.text(.modelTitle))
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct PermissionSheet: View {
    var selected: PermissionPreset
    var copy: ConversationCopy
    var onSelect: (PermissionPreset) -> Void

    var body: some View {
        List {
            ForEach(PermissionPreset.allCases, id: \.self) { preset in
                Button {
                    onSelect(preset)
                } label: {
                    HStack {
                        if preset == .fullAccess {
                            Image(systemName: "shield")
                                .foregroundStyle(DLColor.wait)
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            Text(permissionTitle(preset, copy: copy))
                                .foregroundStyle(preset == .fullAccess ? DLColor.wait : DLColor.label)
                            Text(permissionDetail(preset, copy: copy))
                                .font(DLFont.footnote)
                                .foregroundStyle(DLColor.secondaryLabel)
                        }
                        Spacer()
                        if preset == selected { Image(systemName: "checkmark") }
                    }
                    .frame(minHeight: 44)
                }
            }
            Text(copy.text(.permNote))
                .font(DLFont.footnote)
                .foregroundStyle(DLColor.secondaryLabel)
        }
        .navigationTitle(copy.text(.permTitle))
        .navigationBarTitleDisplayMode(.inline)
    }
}

private func usageIo(_ figures: UsageFigures, copy: ConversationCopy) -> String {
    let input = copy.compact(figures.uncachedInputTokens)
    let cache = copy.compact(figures.cacheReadTokens)
    let output = copy.compact(figures.outputTokens)
    return "\(input) / \(cache) / \(output)"
}

func permissionTitle(_ preset: PermissionPreset, copy: ConversationCopy) -> String {
    switch preset {
    case .readOnly: copy.text(.permRead)
    case .workspaceWrite: copy.text(.permWrite)
    case .fullAccess: copy.text(.permFull)
    }
}

func permissionDetail(_ preset: PermissionPreset, copy: ConversationCopy) -> String {
    switch preset {
    case .readOnly: copy.text(.permReadDetail)
    case .workspaceWrite: copy.text(.permWriteDetail)
    case .fullAccess: copy.text(.permFullDetail)
    }
}

struct AttachSheet: View {
    var copy: ConversationCopy
    var cameraAvailable: Bool
    var onCamera: () -> Void
    var onPhotos: () -> Void

    var body: some View {
        List {
            Button(copy.text(.attachCamera), action: onCamera)
                .disabled(!cameraAvailable)
                .frame(minHeight: 44)
            Button(copy.text(.attachPhotos), action: onPhotos)
                .frame(minHeight: 44)
            Text(copy.text(.attachNote))
                .font(DLFont.footnote)
                .foregroundStyle(DLColor.secondaryLabel)
        }
        .navigationTitle(copy.text(.attachTitle))
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct UsageSheet: View {
    var figures: UsageFigures?
    var copy: ConversationCopy

    var body: some View {
        List {
            if let figures {
                Section {
                    Text(copy.compact(figures.totalTokens))
                        .font(.largeTitle.monospacedDigit())
                    Text(copy.text(.usageTokens))
                        .font(DLFont.footnote)
                        .foregroundStyle(DLColor.secondaryLabel)
                    if let percent = contextUsedPercent(
                        used: figures.contextUsedTokens, window: figures.contextWindowTokens)
                    {
                        ProgressView(
                            value: Double(percent), total: 100,
                            label: { Text(copy.text(.usageContext)) },
                            currentValueLabel: {
                                Text("\(percent)%")
                            })
                    }
                }
                Section {
                    usageLine(copy.text(.usageCache), figures.cacheHitRate.map { "\(Int($0 * 100))%" } ?? "—")
                    usageLine(copy.text(.usageIo), usageIo(figures, copy: copy))
                    usageLine(copy.text(.usageTurns), "\(figures.turns) / \(figures.steps)")
                    usageLine(copy.text(.usageTime), "\(figures.llmMs) / \(figures.toolMs)")
                }
            } else {
                Text(copy.text(.usageEmpty)).foregroundStyle(DLColor.secondaryLabel)
            }
        }
        .navigationTitle(copy.text(.usageTitle))
        .navigationBarTitleDisplayMode(.inline)
    }

    private func usageLine(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title)
            Spacer()
            Text(value).foregroundStyle(DLColor.secondaryLabel).monospacedDigit()
        }
    }
}

struct SubagentPage: View {
    var nodes: [SubagentNode]
    var copy: ConversationCopy
    var animated = true

    var body: some View {
        List {
            if nodes.isEmpty {
                Text(copy.text(.agentsEmpty)).foregroundStyle(DLColor.secondaryLabel)
            }
            ForEach(nodes) { node in
                HStack {
                    Text(node.title.isEmpty ? node.id : node.title)
                        .font(DLFont.headline)
                        .padding(.leading, CGFloat(node.depth) * 16)
                    Spacer()
                    if node.running {
                        if animated {
                            ProgressView().controlSize(.small)
                        } else {
                            Image(systemName: "arrow.triangle.2.circlepath")
                                .foregroundStyle(DLColor.secondaryLabel)
                        }
                    } else {
                        Text(copy.text(.agentsDone))
                            .font(DLFont.footnote)
                            .foregroundStyle(DLColor.ok)
                    }
                }
                .frame(minHeight: 44)
            }
        }
        .navigationTitle(copy.text(.agentsTitle))
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct ShareSheet: View {
    var title: String
    var transcript: String
    var copy: ConversationCopy
    var plainActions = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text(title).font(DLFont.headline)
                Text(transcript).font(DLFont.body)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(DLColor.groupedBackground, in: RoundedRectangle(cornerRadius: 16))
            Button(copy.text(.shareImage)) {}
                .frame(minHeight: 44)
            if plainActions {
                Button(copy.text(.shareText)) {}
                    .frame(minHeight: 44)
            } else {
                ShareLink(item: "\(title)\n\n\(transcript)") {
                    Text(copy.text(.shareText)).frame(maxWidth: .infinity, minHeight: 44)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .navigationTitle(copy.text(.shareTitle))
        .navigationBarTitleDisplayMode(.inline)
        .background(DLColor.background)
    }
}

struct SchedulePage: View {
    var items: [ScheduleTask]
    var sessionID: String
    var copy: ConversationCopy
    var onDelete: (ScheduleTask) -> Void
    @State private var scope: ScheduleScope
    @State private var pendingDelete: ScheduleTask?

    init(
        items: [ScheduleTask], scope: ScheduleScope, sessionID: String, copy: ConversationCopy,
        onDelete: @escaping (ScheduleTask) -> Void = { _ in }
    ) {
        self.items = items
        self.sessionID = sessionID
        self.copy = copy
        self.onDelete = onDelete
        _scope = State(initialValue: scope)
    }

    var body: some View {
        let shown = visibleSchedules(items, scope: scope, sessionID: sessionID)
        List {
            Picker(copy.text(.scheduleTitle), selection: $scope) {
                Text(copy.text(.scheduleSession)).tag(ScheduleScope.session)
                Text(copy.text(.scheduleAll)).tag(ScheduleScope.all)
            }
            .pickerStyle(.segmented)
            if shown.isEmpty {
                Text(copy.text(.scheduleEmpty)).foregroundStyle(DLColor.secondaryLabel)
            }
            ForEach(shown) { task in
                Button {
                    pendingDelete = task
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(task.title?.isEmpty == false ? task.title! : task.id)
                                .foregroundStyle(DLColor.label)
                            Text(scheduleLabel(task, copy: copy))
                                .font(DLFont.footnote)
                                .foregroundStyle(DLColor.secondaryLabel)
                        }
                        Spacer()
                        Image(systemName: "chevron.right")
                            .foregroundStyle(DLColor.tertiaryLabel)
                    }
                    .frame(minHeight: 44)
                }
            }
        }
        .navigationTitle(copy.text(.scheduleTitle))
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog(
            copy.text(.deleteTitle), isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button(copy.text(.deleteConfirm), role: .destructive) {
                if let pendingDelete { onDelete(pendingDelete) }
                pendingDelete = nil
            }
            Button(copy.text(.cancel), role: .cancel) { pendingDelete = nil }
        } message: {
            Text(copy.text(.deleteBody))
        }
    }
}

func scheduleLabel(_ task: ScheduleTask, copy: ConversationCopy) -> String {
    switch scheduleRule(task) {
    case .daily(let time, let zone):
        copy.format(.scheduleDaily, time) + zoneSuffix(zone)
    case .weekly(let days, let time, let zone):
        copy.format(.scheduleWeekly, days.map(String.init).joined(separator: " "), time) + zoneSuffix(zone)
    case .every(let seconds):
        copy.format(.scheduleEvery, "\(max(1, seconds / 60))")
    case .cron(let expression, let zone):
        expression + zoneSuffix(zone)
    case .once:
        copy.text(.scheduleOnce)
    case .raw(let kind):
        kind
    }
}

private func zoneSuffix(_ zone: String) -> String {
    zone.isEmpty ? "" : " · \(zone)"
}

struct GoalEditSheet: View {
    var copy: ConversationCopy
    var onSave: (String, Int) -> Void
    var onClear: () -> Void
    @State private var text: String
    @State private var rounds: Int

    init(
        text: String, rounds: Int, copy: ConversationCopy, onSave: @escaping (String, Int) -> Void = { _, _ in },
        onClear: @escaping () -> Void = {}
    ) {
        self.copy = copy
        self.onSave = onSave
        self.onClear = onClear
        _text = State(initialValue: text)
        _rounds = State(initialValue: rounds)
    }

    var body: some View {
        Form {
            TextField(copy.text(.goalField), text: $text, axis: .vertical)
            Stepper(copy.format(.goalRounds, rounds), value: $rounds, in: 1...1000)
        }
        .navigationTitle(copy.text(.goalTitle))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            Button(copy.text(.save)) { onSave(text, rounds) }
        }
        .safeAreaInset(edge: .bottom) {
            HStack {
                Button(copy.text(.goalClear), action: onClear)
                    .foregroundStyle(DLColor.err)
                    .frame(minHeight: 44)
                Spacer()
            }
            .padding(.horizontal, 16)
            .background(DLColor.background)
        }
    }
}

struct SelectTextPage: View {
    var text: String
    var copy: ConversationCopy

    var body: some View {
        ScrollView {
            Text(text)
                .font(DLFont.body)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .textSelection(.enabled)
        }
        .navigationTitle(copy.text(.selectTitle))
        .navigationBarTitleDisplayMode(.inline)
        .background(DLColor.background)
    }
}

struct CameraCapture: UIViewControllerRepresentable {
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(dismiss: dismiss) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        var dismiss: DismissAction
        init(dismiss: DismissAction) { self.dismiss = dismiss }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { dismiss() }
        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            _ = info
            dismiss()
        }
    }
}

struct DialogCard: View {
    var title: String
    var message: String?
    var field: String? = nil
    var dismiss: String
    var confirm: String
    var destructive: Bool

    var body: some View {
        VStack(spacing: 16) {
            Spacer(minLength: 0)
            VStack(alignment: .leading, spacing: 12) {
                Text(title).font(DLFont.headline)
                if let message {
                    Text(message).font(DLFont.body).foregroundStyle(DLColor.secondaryLabel)
                }
                if let field {
                    Text(field)
                        .font(DLFont.body)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(8)
                        .background(DLColor.fill, in: RoundedRectangle(cornerRadius: 8))
                }
                HStack {
                    Spacer()
                    Button(dismiss) {}
                    Button(confirm) {}
                        .foregroundStyle(destructive ? DLColor.err : DLColor.accent)
                }
            }
            .padding(16)
            .background(DLColor.groupedBackground, in: RoundedRectangle(cornerRadius: 16))
            .padding(24)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(DLColor.background)
    }
}
