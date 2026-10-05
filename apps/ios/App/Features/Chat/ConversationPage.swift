import DLCore
import DLModels
import DLNet
import DLSecurity
import DLUI
import PhotosUI
import SwiftUI
import UIKit

struct ConversationFlowView: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var model: ConversationModel
    var sessions: [SessionSummary] = []

    init(
        hostID: String, sessionID: String, seed: ConversationSeed, sessions: [SessionSummary] = [],
        model: ConversationModel? = nil
    ) {
        self.sessions = sessions
        if let model {
            _model = State(initialValue: model)
        } else {
            _model = State(
                initialValue: ConversationModel(
                    hostID: hostID, sessionID: sessionID, seed: seed,
                    service: ConversationLiveService(hostID: hostID), box: .live()))
        }
    }

    var body: some View {
        ConversationPage(model: model, sessions: sessions)
            .onChange(of: scenePhase) { _, phase in
                let mapped: AppPhase =
                    switch phase {
                    case .active: .active
                    case .background: .background
                    default: .inactive
                    }
                Task { await model.setPhase(mapped) }
            }
    }
}

struct ConversationPage: View {
    @Bindable var model: ConversationModel
    var sessions: [SessionSummary] = []
    /// Screenshot path: no `.task`, no stream, no display link, no web view, no share sheet.
    var staticSnapshot = false
    var pinsToTail = false
    /// Legacy I4.3a fixtures explicitly opt out to preserve their baseline content.
    var showsStatusSlot = true
    /// 旧截图走 staticSnapshot，不带输入区。新的 4.3 / 4.4 截图显式打开。
    var showsComposer: Bool? = nil
    /// 截图直接给决策，生产路径从请求归并里取最新一条。
    var decisionPreview: PhoneDecision? = nil
    /// Wide snapshots open the changes inspector with sample files.
    var presentChanges = false
    @State var statusExpanded = false
    @State private var draft = ""
    @State private var decisionPulse = 0
    @State private var showTrajectory = false
    @State private var showAgents = false
    @State private var showSchedule = false
    @State private var showSelectText = false
    @State private var showChanges = false
    @State private var showFiles = false
    @State private var showPreview = false
    @Namespace private var changesZoom
    @State private var sheet: ChatSurface?
    @State private var renamePresented = false
    @State private var renameText = ""
    @State private var confirmFull = false
    @State private var showCamera = false
    @State private var showPhotos = false
    @State private var photo: PhotosPickerItem?
    @State private var selectedText = ""
    @State private var permission = PermissionPreset.workspaceWrite
    @State private var modelRowsLive: [ModelRow] = []
    @State private var selectedModelID = ""
    @State private var effort = ""
    @State private var contextPercent: Int?
    @State private var schedules: [ScheduleTask] = []
    @State private var scope = ScheduleScope.session
    var draftDirectory: URL?
    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let copy = ConversationCopy(locale: locale)
        let page = screen(copy)
            .navigationTitle(displayTitle(copy))
            .navigationSubtitle(copy.subtitle(workspace: model.workspaceName, phase: model.phase))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbar(copy) }
        if staticSnapshot {
            Group {
                if presentChanges {
                    page.inspector(isPresented: .constant(true)) {
                        ChangesPage(
                            files: ReviewScreen.sampleFiles, turn: 3, canPrevious: true, canNext: false,
                            copy: ReviewCopy(locale: locale)
                        )
                        .inspectorColumnWidth(min: 280, ideal: 360, max: 480)
                    }
                } else {
                    page
                }
            }
            .transaction { $0.disablesAnimations = true }
        } else {
            page
                .navigationDestination(isPresented: $showTrajectory) {
                    TrajectoryPage(messages: model.messages)
                }
                .navigationDestination(isPresented: $showAgents) {
                    SubagentPage(nodes: flattenSubagents(sessions: sessions, rootID: model.sessionID), copy: copy)
                }
                .navigationDestination(isPresented: $showSchedule) {
                    SchedulePage(items: schedules, scope: scope, sessionID: model.sessionID, copy: copy) { task in
                        Task { await model.serviceDeleteSchedule(task.id) }
                        schedules.removeAll { $0.id == task.id }
                    }
                }
                .navigationDestination(isPresented: $showSelectText) {
                    SelectTextPage(text: selectedText, copy: copy)
                }
                .modifier(
                    ChangesPresentation(
                        regular: sizeClass == .regular, presented: $showChanges, copy: ReviewCopy(locale: locale),
                        zoom: changesZoom)
                )
                .navigationDestination(isPresented: $showFiles) {
                    FilesPage(path: "", entries: [], copy: ReviewCopy(locale: locale))
                }
                .navigationDestination(isPresented: $showPreview) {
                    PreviewPage(
                        previews: [], copy: ReviewCopy(locale: locale), loadsWeb: true,
                        forward: model.previewForwarder())
                }
                .sheet(item: $sheet) { item in
                    NavigationStack { sheetPage(item, copy: copy) }
                        .onKeyPress(.escape) {
                            sheet = nil
                            return .handled
                        }
                }
                .onKeyPress(.escape) { dismissPresented() }
                .alert(copy.text(.renameTitle), isPresented: $renamePresented) {
                    TextField(copy.text(.renameField), text: $renameText)
                    Button(copy.text(.cancel), role: .cancel) {}
                    Button(copy.text(.save)) { Task { try? await model.serviceRename(renameText) } }
                }
                .confirmationDialog(
                    copy.text(.permConfirmTitle), isPresented: $confirmFull, titleVisibility: .visible
                ) {
                    Button(copy.text(.permEnable), role: .destructive) {
                        permission = .fullAccess
                        Task { await model.servicePermission(PermissionPreset.fullAccess.rawValue) }
                    }
                    Button(copy.text(.cancel), role: .cancel) {}
                } message: {
                    Text(copy.text(.permConfirmBody))
                }
                .photosPicker(isPresented: $showPhotos, selection: $photo, matching: .images)
                .sheet(isPresented: $showCamera) { CameraCapture() }
                .task { await model.start() }
                .onDisappear { Task { await model.stop() } }
                .onChange(of: sheet) { _, item in
                    guard let item else { return }
                    Task { await loadSheet(item) }
                }
        }
    }

    private var composerOn: Bool { showsComposer ?? !staticSnapshot }

    private var decision: PhoneDecision? {
        decisionPreview ?? pendingPhoneDecision(model.status.requests.messages)
    }

    private func screen(_ copy: ConversationCopy) -> some View {
        column(copy)
            .modifier(
                ComposerInset(on: composerOn) {
                    ConversationBar(
                        decision: decision,
                        draft: draft,
                        copy: copy,
                        onDraft: { text in
                            draft = text
                            if let draftDirectory {
                                ComposerDraftStore(directory: draftDirectory).save(hostID: model.hostID, text: text)
                            }
                        },
                        onSend: { Task { await send(copy) } },
                        onEscape: { _ = dismissPresented() },
                        onSecondary: { Task { await decide(allow: false) } },
                        onPrimary: { Task { await decide(allow: true) } },
                        solidSnapshot: staticSnapshot,
                        suggestions: decision == nil ? slashSuggestions(draft: draft, copy: copy) : [],
                        onSuggestion: { pickSlash($0, copy: copy) },
                        showsAttach: !staticSnapshot && decision == nil,
                        attachTitle: copy.text(.attachTitle),
                        onAttach: { sheet = .attach }
                    )
                    .padding(.horizontal, 12)
                    .padding(.bottom, 8)
                    .fixedSize(horizontal: false, vertical: true)
                }
            )
            .sensoryFeedback(.success, trigger: decisionPulse)
            .onAppear {
                guard draft.isEmpty, let draftDirectory else { return }
                draft = ComposerDraftStore(directory: draftDirectory).load(hostID: model.hostID)
            }
            .onChange(of: model.status.kind) { _, _ in statusExpanded = false }
            .onChange(of: model.status.goal?.ref?.id) { _, _ in statusExpanded = false }
    }

    private func column(_ copy: ConversationCopy) -> some View {
        VStack(spacing: 0) {
            if showsStatusSlot {
                ConversationStatusView(state: model.status, copy: copy, expanded: $statusExpanded)
            }
            if model.loadFailed && (!showsStatusSlot || model.status.kind != .disconnected) {
                DLBanner(copy.text(.loadFailed), systemImage: "wifi.exclamationmark", iconIsError: true)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
            }
            if model.rows.isEmpty {
                DLEmptyState(title: copy.text(.empty), systemImage: "bubble.left.and.bubble.right")
            } else {
                MessageStreamView(
                    rows: model.rows,
                    chrome: MessageChrome(
                        copy: copy,
                        reduceMotion: reduceMotion || staticSnapshot,
                        allowsWeb: !staticSnapshot,
                        images: model.images,
                        staticSnapshot: staticSnapshot,
                        onToggle: { model.toggleProcess($0) },
                        onCopy: { model.copyAssistant($0) },
                        onRegenerate: { model.regenerate($0) },
                        onSuggest: { model.suggest($0) },
                        onViewChanges: { seq in
                            model.viewChanges(seq: seq)
                            if !staticSnapshot { showChanges = true }
                        },
                        onLoadImage: { url in Task { await model.loadImage(url) } },
                        onSelectText: staticSnapshot
                            ? nil
                            : { text in
                                selectedText = text
                                showSelectText = true
                            },
                        changesNamespace: staticSnapshot ? nil : changesZoom),
                    pinsToTail: staticSnapshot ? pinsToTail : true,
                    pumpsFrames: !staticSnapshot,
                    onFrame: { model.drainFrame() }, usesSoftTopEdge: showsStatusSlot
                )
                .scrollEdgeEffectStyle(showsStatusSlot ? .soft : nil, for: .top)
                .opacity(composerOn && decision != nil ? 0.42 : 1)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(DLColor.background)
    }

    private func dismissPresented() -> KeyPress.Result {
        if sheet != nil {
            sheet = nil
            return .handled
        }
        if showCamera {
            showCamera = false
            return .handled
        }
        if showPhotos {
            showPhotos = false
            return .handled
        }
        if showChanges {
            showChanges = false
            return .handled
        }
        if showFiles {
            showFiles = false
            return .handled
        }
        if showPreview {
            showPreview = false
            return .handled
        }
        if showTrajectory {
            showTrajectory = false
            return .handled
        }
        if showAgents {
            showAgents = false
            return .handled
        }
        if showSchedule {
            showSchedule = false
            return .handled
        }
        if showSelectText {
            showSelectText = false
            return .handled
        }
        if renamePresented {
            renamePresented = false
            return .handled
        }
        if confirmFull {
            confirmFull = false
            return .handled
        }
        return .ignored
    }

    private func send(_ copy: ConversationCopy) async {
        _ = copy
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !staticSnapshot else { return }
        if isDangerPermissionCommand(text) {
            confirmFull = true
            return
        }
        do {
            try await model.serviceSend(text)
            draft = ""
            if let draftDirectory {
                ComposerDraftStore(directory: draftDirectory).save(hostID: model.hostID, text: "")
            }
        } catch {
            // 失败或中途被回收都留着草稿，回来后回填，不自动重发。
        }
    }

    private func decide(allow: Bool) async {
        guard !staticSnapshot, let decision else { return }
        do {
            switch decision {
            case .approval(let message):
                guard let id = message.approvalId else { return }
                try await model.serviceApproval(id: id, outcome: allow ? "allowed-once" : "rejected")
            case .question(let message):
                guard allow, let id = message.questionRpcId else { return }
                try await model.serviceQuestion(rpcID: id, answer: draft)
            }
            decisionPulse += 1
        } catch {}
    }

    @ViewBuilder private func sheetPage(_ item: ChatSurface, copy: ConversationCopy) -> some View {
        switch item {
        case .model:
            ModelSheet(
                rows: modelRowsLive, selectedID: selectedModelID, effort: effort, contextPercent: contextPercent,
                copy: copy
            ) { row, next in
                selectedModelID = row.id
                if let next { effort = next }
                Task { await model.serviceSelectModel(provider: row.provider, model: row.id, effort: next) }
            }
        case .permission:
            PermissionSheet(selected: permission, copy: copy) { preset in
                if preset.needsConfirmation {
                    confirmFull = true
                } else {
                    permission = preset
                    Task { await model.servicePermission(preset.rawValue) }
                }
            }
        case .attach:
            AttachSheet(
                copy: copy,
                cameraAvailable: UIImagePickerController.isSourceTypeAvailable(.camera),
                onCamera: {
                    sheet = nil
                    showCamera = true
                },
                onPhotos: {
                    sheet = nil
                    showPhotos = true
                })
        case .usage:
            UsageSheet(
                figures: usageFigures(
                    usage: model.stats?.tokenUsage, stats: model.stats?.sessionStats,
                    pressure: model.stats?.contextPressure, breakdown: model.stats?.contextBreakdown),
                copy: copy)
        case .share:
            ShareSheet(title: displayTitle(copy), transcript: transcript(copy), copy: copy)
        case .goal:
            GoalEditSheet(
                text: model.status.goal?.objective ?? "", rounds: model.status.goal?.maxGoalRounds ?? 8, copy: copy,
                onSave: { text, rounds in Task { await model.serviceEditGoal(objective: text, rounds: rounds) } },
                onClear: { Task { await model.serviceClearGoal() } })
        default:
            EmptyView()
        }
    }

    private func loadSheet(_ item: ChatSurface) async {
        switch item {
        case .model:
            let rows = await model.serviceModels()
            modelRowsLive = rows
            if selectedModelID.isEmpty { selectedModelID = rows.first?.id ?? "" }
            if effort.isEmpty { effort = rows.first?.defaultEffort ?? rows.first?.efforts.first ?? "" }
            if let pressure = model.stats?.contextPressure {
                contextPercent = contextUsedPercent(
                    used: pressure.projectedTokens ?? pressure.pressureTokens ?? 0, window: pressure.contextWindow ?? 0)
            }
        default:
            break
        }
    }

    private func pickSlash(_ trigger: String, copy: ConversationCopy) {
        let entries = paletteEntries(
            title: { paletteCopy($0, copy: copy) }, detail: { paletteCopy($0, copy: copy) })
        guard let command = entries.first(where: { $0.command.trigger == trigger })?.command else { return }
        switch resolvedPick(command) {
        case .insert(let text):
            draft = text
        case .submit(let text):
            draft = text
            Task { await send(copy) }
        case .local(let kind):
            switch kind {
            case .model: sheet = .model
            case .permission: sheet = .permission
            case .trace: showTrajectory = true
            case .newSession, .search: dismiss()
            case .chat, .settings: break
            }
        }
    }

    private func transcript(_ copy: ConversationCopy) -> String {
        var lines: [(speaker: String, text: String)] = []
        for message in model.messages {
            guard let text = message.text, !text.isEmpty else { continue }
            lines.append((speaker: message.role ?? "", text: text))
        }
        return shareTranscript(title: displayTitle(copy), lines: lines)
    }

    private func displayTitle(_ copy: ConversationCopy) -> String {
        let title = inboxDisplayTitle(model.title).trimmingCharacters(in: .whitespacesAndNewlines)
        return title.isEmpty ? copy.text(.untitled) : title
    }

    @ToolbarContentBuilder private func toolbar(_ copy: ConversationCopy) -> some ToolbarContent {
        // The trailing pair is one glass capsule in production. That capsule snapshots empty,
        // so the screenshot path keeps the same items without the shared glass background.
        if staticSnapshot {
            toolbarItems(copy).sharedBackgroundVisibility(.hidden)
        } else {
            toolbarItems(copy)
        }
    }

    private func toolbarItems(_ copy: ConversationCopy) -> some ToolbarContent {
        ToolbarItemGroup(placement: .topBarTrailing) {
            Button {
                model.noteDiff()
            } label: {
                if let added = model.added, let deleted = model.deleted {
                    Text("\(copy.format(.added, added))  \(copy.format(.deleted, deleted))")
                        .font(DLFont.mono(DLFont.footnote))
                } else {
                    Image(systemName: "plus.forwardslash.minus")
                }
            }
            .accessibilityLabel(copy.format(.diffBadge, model.added ?? 0, model.deleted ?? 0))
            Menu {
                Section(copy.text(.menuView)) {
                    Button(copy.text(.menuChanges)) { showChanges = true }
                    Button(copy.text(.menuFiles)) { showFiles = true }
                    Button(copy.text(.menuTrajectory)) { showTrajectory = true }
                    Button(copy.text(.menuAgents)) { showAgents = true }
                    Button(copy.text(.menuUsage)) { sheet = .usage }
                    Button(copy.text(.menuPreview)) { showPreview = true }
                }
                Section(copy.text(.menuActions)) {
                    Button(copy.text(.menuGoal)) { sheet = .goal }
                    Button(copy.text(.menuSchedule)) {
                        Task {
                            schedules = await model.serviceSchedules(all: false)
                            showSchedule = true
                        }
                    }
                    Button(copy.text(.menuRename)) {
                        renameText = displayTitle(copy)
                        renamePresented = true
                    }
                    Button(copy.text(.menuFork)) { Task { _ = try? await model.serviceFork() } }
                    Button(copy.text(.menuShare)) { sheet = .share }
                }
            } label: {
                Image(systemName: "ellipsis")
            }
            .accessibilityLabel(copy.text(.more))
        }
    }
}

private struct ChangesPresentation: ViewModifier {
    var regular: Bool
    @Binding var presented: Bool
    var copy: ReviewCopy
    var zoom: Namespace.ID

    func body(content: Content) -> some View {
        if regular {
            content.inspector(isPresented: $presented) {
                ChangesPage(files: [], turn: 1, canPrevious: false, canNext: false, copy: copy)
                    .inspectorColumnWidth(min: 280, ideal: 360, max: 480)
            }
        } else {
            content.navigationDestination(isPresented: $presented) {
                ChangesPage(files: [], turn: 1, canPrevious: false, canNext: false, copy: copy)
                    .navigationTransition(.zoom(sourceID: 0, in: zoom))
            }
        }
    }
}

private struct ComposerInset<Bar: View>: ViewModifier {
    var on: Bool
    @ViewBuilder var bar: () -> Bar

    func body(content: Content) -> some View {
        if on {
            content.safeAreaInset(edge: .bottom, spacing: 0, content: bar)
        } else {
            content
        }
    }
}

@MainActor func conversationSeed(sessionID: String, model: InboxModel) -> ConversationSeed {
    let session =
        model.sessions.first { $0.sessionId == sessionID } ?? model.archivedSessions.first { $0.sessionId == sessionID }
    return ConversationSeed(
        title: inboxDisplayTitle(session?.title),
        workspace: session?.cwd ?? "",
        running: session?.running == true,
        step: session?.activity?.step,
        added: session?.lastResult?.added,
        deleted: session?.lastResult?.deleted,
        stoppedReason: session?.stoppedReason,
        awaitingInput: session?.awaitingInput == true)
}
