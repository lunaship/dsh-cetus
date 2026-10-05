import DLCore
import DLModels
import DLNet
import DLSecurity
import DLUI
import SwiftUI

struct ConversationFlowView: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var model: ConversationModel

    init(hostID: String, sessionID: String, seed: ConversationSeed, model: ConversationModel? = nil) {
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
        ConversationPage(model: model)
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
    /// Screenshot path: no `.task`, no stream, no display link, no web view, no share sheet.
    var staticSnapshot = false
    var pinsToTail = false
    /// Legacy I4.3a fixtures explicitly opt out to preserve their baseline content.
    var showsStatusSlot = true
    /// 旧截图走 staticSnapshot，不带输入区。新的 4.3 / 4.4 截图显式打开。
    var showsComposer: Bool? = nil
    /// 截图直接给决策，生产路径从请求归并里取最新一条。
    var decisionPreview: PhoneDecision? = nil
    @State var statusExpanded = false
    @State private var draft = ""
    @State private var decisionPulse = 0
    @State private var showTrajectory = false
    var draftDirectory: URL?
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let copy = ConversationCopy(locale: locale)
        let page = screen(copy)
            .navigationTitle(displayTitle(copy))
            .navigationSubtitle(copy.subtitle(workspace: model.workspaceName, phase: model.phase))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbar(copy) }
        if staticSnapshot {
            page.transaction { $0.disablesAnimations = true }
        } else {
            page
                .navigationDestination(isPresented: $showTrajectory) {
                    TrajectoryPage(messages: model.messages)
                }
                .task { await model.start() }
                .onDisappear { Task { await model.stop() } }
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
                        onSecondary: { Task { await decide(allow: false) } },
                        onPrimary: { Task { await decide(allow: true) } },
                        solidSnapshot: staticSnapshot
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
                        onViewChanges: { model.viewChanges(seq: $0) },
                        onLoadImage: { url in Task { await model.loadImage(url) } }),
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

    private func send(_ copy: ConversationCopy) async {
        _ = copy
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !staticSnapshot else { return }
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
                    Button(copy.text(.menuChanges)) {}
                    Button(copy.text(.menuFiles)) {}
                    Button(copy.text(.menuTrajectory)) { showTrajectory = true }
                    Button(copy.text(.menuAgents)) {}
                    Button(copy.text(.menuUsage)) {}
                    Button(copy.text(.menuPreview)) {}
                }
                Section(copy.text(.menuActions)) {
                    Button(copy.text(.menuGoal)) {}
                    Button(copy.text(.menuSchedule)) {}
                    Button(copy.text(.menuRename)) {}
                    Button(copy.text(.menuFork)) {}
                    Button(copy.text(.menuShare)) {}
                }
            } label: {
                Image(systemName: "ellipsis")
            }
            .accessibilityLabel(copy.text(.more))
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
