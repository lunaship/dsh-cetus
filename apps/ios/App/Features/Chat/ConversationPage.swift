import DLCore
import DLModels
import DLNet
import DLSecurity
import DLUI
import PhotosUI
import SwiftUI
import UIKit

struct ConversationComposerSurface: Equatable, Sendable {
    var placement: String
    var kind: String?
    var title: String?
    var expanded: Bool
    var showsPlan: Bool
    var material: String
    var collapsedWidth: String
    var expandedLimit: String
    var decisionVisible: Bool
}

extension ConversationPage {
    func composerSurface(copy: ConversationCopy, expanded: Bool) -> ConversationComposerSurface {
        let kind = model.status.kind
        return ConversationComposerSurface(
            placement: showsStatusSlot ? "composer" : "hidden",
            kind: kind.map { String(describing: $0) },
            title: kind.map {
                ConversationStatusView.statusTitle($0, state: model.status, copy: copy, expanded: expanded)
            },
            expanded: kind == .goal && expanded,
            showsPlan: kind == .goal && expanded && !model.status.plan.isEmpty,
            material: kind == nil ? "none" : "capsule",
            collapsedWidth: kind == nil ? "none" : "hug",
            expandedLimit: kind == .goal && expanded ? "half-screen" : "none",
            decisionVisible: decision != nil
        )
    }
}

struct ConversationFlowView: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var model: ConversationModel
    var sessions: [SessionSummary] = []
    var sharePrefill: SharePrefill?

    init(
        hostID: String, sessionID: String, seed: ConversationSeed, sessions: [SessionSummary] = [],
        sharePrefill: SharePrefill? = nil, model: ConversationModel? = nil
    ) {
        self.sharePrefill = sharePrefill
        self.sessions = sessions
        if let model {
            _model = State(initialValue: model)
        } else {
            _model = State(
                initialValue: ConversationModel(
                    hostID: hostID, sessionID: sessionID, seed: seed,
                    service: ConversationLiveService(
                        hostID: hostID,
                        store: PerformanceLaunchFixture.isRequested
                            || PerformanceLaunchFixture.unsignedStorage != nil
                            ? PerformanceLaunchFixture.hostStore() : HostStore(),
                        backgroundTasks: SystemBackgroundTasks()),
                    box: PerformanceLaunchFixture.isRequested
                        ? PerformanceLaunchFixture.snapshotBox() : .live()))
        }
    }

    var body: some View {
        ConversationPage(model: model, sessions: sessions, sharePrefill: sharePrefill)
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
    var sharePrefill: SharePrefill? = nil
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
    /// 草稿写入的合并入口（C02 要求 6）：不是每个字符都同步写盘。
    /// 截图路径用 `.disabled` 空壳，不碰开发者的真实草稿。
    @State private var drafts = ComposerDraftController.disabled
    @State private var questionForm = QuestionForm(questions: [])
    @State private var questionRPC: String?
    @State private var decisionPulse = 0
    @State private var showTrajectory = false
    @State private var showAgents = false
    @State private var showSchedule = false
    @State private var showSelectText = false
    @State private var showChanges = false
    @State private var showFiles = false
    @State private var showFilePreview = false
    @State private var showPreview = false
    @Namespace private var changesZoom
    @State private var sheet: ChatSurface?
    @State private var renamePresented = false
    @State private var renameText = ""
    @State private var confirmFull = false
    @State private var showCamera = false
    @State private var showPhotos = false
    @State private var showDocuments = false
    @State private var photo: PhotosPickerItem?
    @State private var attachments: [PromptImage] = []
    @State private var attachmentNotice: ChatText?
    // MARK: - C03 提交状态
    /// 当前 draft 的修订号：每次用户编辑都会前进，用来区分"提交的那份"与"提交期间新写的"。
    @State private var draftRevision = 0
    /// 写盘失败要提示：内存副本还在，但不能让用户以为已保存（C02 要求 6）。
    @State private var draftWriteFailed = false
    /// 问题自由回答的独立输入框（C06）：不再复用正文 `draft`，
    /// 否则切题 / 提交会把用户正在写的正文一起冲掉。
    @State private var answerText = ""
    /// 决策（审批 / 问题提交）在途锁，避免重复点击（C06）。
    @State private var decisionBusy = false
    @State private var decisionNotice: ChatText?
    /// C06 10.2.8：决策提示的格式化参数（如出错题号）。空表示无参数。
    @State private var decisionNoticeArguments: [CVarArg] = []
    @State private var submission: SubmissionState = .idle
    @State private var submissionNotice: ChatText?
    // MARK: - C04 跟滚
    /// 上翻期间来了新消息 → 显示"回到最新"入口。
    @State private var showNewMessagesPill = false
    @StateObject private var streamCoordinator = MessageStreamCoordinator()
    @State private var selectedText = ""
    @State private var permission = PermissionPreset.workspaceWrite
    @State private var modelRowsLive: [ModelRow] = []
    @State private var selectedModelID = ""
    @State private var effort = ""
    @State private var contextPercent: Int?
    @State private var schedules: [ScheduleTask] = []
    @State private var scope = ScheduleScope.session
    /// 草稿仓库走环境值注入（C02）。旧代码这里是 `draftDirectory: URL?`，
    /// 但生产路径 `ConversationFlowView` 不传它 → 恒为 nil → 草稿静默不落盘。
    @Environment(\.composerDraftStore) private var draftStore
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
        if staticSnapshot && presentChanges {
            // The system inspector animates and does not settle to the same pixels twice.
            // The snapshot pins the same list beside the conversation. Production still uses `.inspector`.
            HStack(spacing: 0) {
                page
                NavigationStack {
                    ChangesPage(
                        files: ReviewScreen.sampleFiles, turn: 3, canPrevious: true, canNext: false,
                        copy: ReviewCopy(locale: locale)
                    )
                }
                .frame(width: 320)
            }
            .transaction { $0.disablesAnimations = true }
        } else if staticSnapshot {
            page.transaction { $0.disablesAnimations = true }
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
                        zoom: changesZoom, model: model,
                        // C08：引用只预填输入区，不自动发送。
                        onAsk: { reference in
                            draft = ChangesTurnNavigator.appending(reference, to: draft)
                            draftRevision += 1
                            drafts.update(reference, kind: .reference)
                            drafts.update(draft, kind: .prompt)
                            showChanges = false
                        })
                )
                .navigationDestination(isPresented: $showFiles) {
                    FilesPage(
                        path: model.filesPath,
                        entries: model.fileEntries,
                        copy: ReviewCopy(locale: locale),
                        loading: model.filesLoading,
                        error: model.filesError,
                        truncated: model.filesTruncated,
                        unsupported: model.filesUnsupported,
                        openingFile: model.openingFile,
                        returnAnchor: model.filesReturnAnchor,
                        onBreadcrumb: { model.loadFiles(path: $0) },
                        onEnterDir: { model.loadFiles(path: $0) },
                        onOpenFile: { openFile($0, copy: copy) },
                        onRetry: { model.loadFiles(path: model.filesPath) }
                    )
                    .navigationDestination(isPresented: $showFilePreview) {
                        if let file = model.openedFile {
                            FilePreviewPage(
                                path: file.path, text: file.text, kind: file.kind, fileURL: file.url,
                                discarded: file.failed, liveShare: !file.failed, copy: ReviewCopy(locale: locale),
                                onQuote: {
                                    let reference = "@\"\(file.path)\""
                                    draft = ChangesTurnNavigator.appending(reference, to: draft)
                                    draftRevision += 1
                                    // 引用走自己的槽位（.reference），不与正文草稿互相覆盖。
                                    drafts.update(reference, kind: .reference)
                                    drafts.update(draft, kind: .prompt)
                                    showFilePreview = false
                                    showFiles = false
                                },
                                onCopyPath: { UIPasteboard.general.string = file.path }, showQuote: !file.failed)
                        }
                    }
                }
                .navigationDestination(isPresented: $showPreview) {
                    PreviewPage(
                        previews: model.previews, copy: ReviewCopy(locale: locale), loadsWeb: true,
                        forward: model.previewForwarder(),
                        websocket: model.previewWebSocketOpener(),
                        loading: model.previewsLoading,
                        error: model.previewsError,
                        detectedPorts: model.detectedPreviewPorts,
                        onRetry: { model.loadPreviews() },
                        refreshApproved: { try await model.approvedPreviews() })
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
                .sheet(isPresented: $showDocuments) {
                    DocumentFilePicker { urls in
                        acceptFiles(urls)
                    }
                }
                .alert(
                    copy.text(attachmentNotice ?? .attachUnsupported),
                    isPresented: Binding(
                        get: { attachmentNotice != nil },
                        set: { if !$0 { attachmentNotice = nil } }
                    )
                ) {
                    Button(copy.text(.cancel), role: .cancel) { attachmentNotice = nil }
                }
                // C03：发送失败要给可见、可操作的反馈。输入一律保留。
                .alert(
                    submissionNoticeText(copy),
                    isPresented: Binding(
                        get: { submissionNotice != nil },
                        set: { if !$0 { submissionNotice = nil } }
                    )
                ) {
                    Button(copy.text(.retry)) { Task { await send(copy) } }
                    Button(copy.text(.cancel), role: .cancel) { submissionNotice = nil }
                }
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
        decisionPreview ?? pendingPhoneDecision(model.messages, requests: model.status.requests.messages)
    }

    private var activeQuestion: RequestMessage? {
        guard case .question(let message) = decision else { return nil }
        return message
    }

    /// 当前审批请求的 id，用于感知"审批出现 / 消失"。
    private var decisionApprovalID: String? {
        guard case .approval(let message) = decision else { return nil }
        return message.approvalId
    }

    // MARK: - 草稿

    /// 把环境注入的仓库装进控制器。截图路径不装（保持 `.disabled` 空壳）。
    private func installDrafts() {
        guard !drafts.isEnabled, !staticSnapshot else { return }
        drafts = ComposerDraftController(
            store: draftStore,
            key: ComposerDraftKey(hostID: model.hostID, sessionID: model.sessionID))
        drafts.onWriteFailure = { _ in draftWriteFailed = true }
    }

    /// 审批出现 → 冻结正文草稿（不清空）；审批消失 → 解冻并把内存文字补写回去。
    private func syncDecisionFreeze() {
        if decisionApprovalID != nil {
            drafts.freeze(.prompt)
        } else {
            drafts.unfreeze(.prompt, text: draft)
        }
    }

    /// 问题卡片出现时把回答草稿存进 `.answer` 槽位，与正文 `.prompt` 分开（C02 要求 2）。
    private func syncQuestionDraft() {
        if activeQuestion != nil {
            // 正文草稿冻结：输入区被问题卡片占用，回来的文字仍在内存里（不清空）。
            drafts.freeze(.prompt)
            persistQuestionDraft()
        } else {
            drafts.unfreeze(.prompt, text: draft)
            drafts.clear(.answer)
        }
    }

    private func screen(_ copy: ConversationCopy) -> some View {
        column(copy)
            .modifier(
                ComposerInset(on: composerOn) {
                    if showsStatusSlot {
                        ConversationStatusView(state: model.status, copy: copy, expanded: $statusExpanded)
                    }
                    if activeQuestion != nil, !staticSnapshot {
                        questionChoices(copy)
                        questionNavigator(copy)
                    }
                    ConversationBar(
                        decision: decision,
                        draft: draft,
                        copy: copy,
                        onDraft: { text in
                            // 用户每次编辑都前进修订号：提交期间新写的内容
                            // 因此与"被提交的那份快照"区分开（C03 要求 2）。
                            if text != draft { draftRevision += 1 }
                            draft = text
                            // 只排队，不每个字符写盘（C02 要求 6）。
                            drafts.update(text, kind: .prompt)
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
                        onAttach: { sheet = .attach },
                        isSending: submission.busy,
                        placeholder: composerPlaceholderText(copy),
                        decisionHandled: isDecisionHandled,
                        decisionPosition: decisionPositionText(copy),
                        decisionNotice: decisionNoticeText(copy),
                        // C06 10.2.7：末题提交由题目导航区负责，决策栏不再重复放发送。
                        questionUsesNavigatorSubmit: activeQuestion != nil,
                        decisionBusy: decisionBusy
                    )
                    .padding(.horizontal, 12)
                    .padding(.bottom, 8)
                    .fixedSize(horizontal: false, vertical: true)
                }
            )
            .sensoryFeedback(.success, trigger: decisionPulse)
            .onAppear {
                installDrafts()
                if let sharePrefill, sharePrefill.target == .session(model.sessionID) {
                    // C13: merge the share into the saved draft instead of replacing it.
                    // installDrafts() does not restore text, so read the saved prompt first.
                    let base = draft.isEmpty ? drafts.restored(.prompt) : draft
                    draft = ShareInbox.merging(draft: base, shared: sharePrefill.text).text
                    attachments = ShareInbox.mergingImages(existing: attachments, shared: sharePrefill.images)
                    return
                }
                guard draft.isEmpty else { return }
                draft = drafts.restored(.prompt)
            }
            // 审批 / 问题卡片出现：冻结正文草稿（不清空，用户回来文字还在）。
            // 离开时解冻，让内存里的文字继续落盘。
            .onChange(of: activeQuestion?.questionRpcId) { _, _ in syncQuestionDraft() }
            .onChange(of: decisionApprovalID) { _, _ in syncDecisionFreeze() }
            .onDisappear { drafts.flush() }
            // 进后台前落盘：进程随后可能被回收，草稿必须已经在盘上（C02 要求 6）。
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)) { _ in
                drafts.flush()
            }
            .onChange(of: model.status.kind) { _, _ in statusExpanded = false }
            .onChange(of: model.status.goal?.ref?.id) { _, _ in statusExpanded = false }
    }

    private func column(_ copy: ConversationCopy) -> some View {
        VStack(spacing: 0) {
            if model.loadFailed && model.status.kind != .disconnected {
                DLBanner(copy.text(.loadFailed), systemImage: "wifi.exclamationmark", iconIsError: true)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
            }
            // C06：决策提交失败要显式提示，静默吞掉会让用户以为已批准。
            if !staticSnapshot, let decisionNotice, decision != nil {
                DLBanner(copy.text(decisionNotice), systemImage: "exclamationmark.triangle", iconIsError: true)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
            }
            // C02 要求 6：写盘失败要提示，内存副本还在但不能当用户已保存。
            if draftWriteFailed {
                DLBanner(copy.text(.draftNotSaved), systemImage: "exclamationmark.triangle", iconIsError: true)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
            }
            if !staticSnapshot, model.status.kind == .disconnected {
                Button(copy.text(.statusRetry)) { Task { await model.retryConnection() } }
                    .frame(minHeight: 44)
                    .padding(.horizontal, 16)
            }
            // C04：分页入口从"导航下方常驻占位"移到滚动内容顶部
            // （见 MessageStreamView 的 header）。这里不再占固定阅读空间。
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
                    onFrame: { model.drainFrame() }, usesSoftTopEdge: showsStatusSlot,
                    onReachTop: { Task { await model.loadOlder() } },
                    hasOlder: model.hasOlder,
                    olderFailed: model.olderFailed,
                    loadingOlder: model.loadingOlder,
                    onNewMessagesWhileHeld: { showNewMessagesPill = true },
                    coordinator: streamCoordinator
                )
                .scrollEdgeEffectStyle(showsStatusSlot ? .soft : nil, for: .top)
                .opacity(composerOn && decision != nil ? 0.42 : 1)
                // C04：上翻读历史时，新消息到达给一个紧凑入口。
                // 它在输入区**上方**，不遮挡输入区与正文（方案 §8 要求 2）。
                .overlay(alignment: .bottomTrailing) {
                    if showNewMessagesPill {
                        Button {
                            streamCoordinator.scrollToLatest()
                            showNewMessagesPill = false
                        } label: {
                            Label(copy.text(.newMessages), systemImage: "arrow.down")
                                .font(.subheadline)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.small)
                        .padding(.trailing, 16)
                        .padding(.bottom, 8)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(DLColor.background)
    }

    private func questionChoices(_ copy: ConversationCopy) -> some View {
        let options = questionForm.current?.options ?? []
        let selected = questionForm.current.map { questionForm.draft(for: $0).selected } ?? []
        return VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(options.enumerated()), id: \.offset) { entry in
                let value = QuestionForm.optionValue(entry.element) ?? ""
                let label = entry.element.label ?? value
                Button {
                    toggleQuestionOption(value)
                } label: {
                    HStack {
                        Image(systemName: selected.contains(value) ? "checkmark.circle.fill" : "circle")
                        Text(label.isEmpty ? value : label)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(minHeight: 44)
                }
                .disabled(label.isEmpty && value.isEmpty)
            }
        }
        .padding(.horizontal, 16)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(copy.text(.question))
    }

    private func toggleQuestionOption(_ value: String) {
        guard let question = questionForm.current, !value.isEmpty else { return }
        var selected = questionForm.draft(for: question).selected
        if question.multiple == true {
            if let index = selected.firstIndex(of: value) {
                selected.remove(at: index)
            } else {
                selected.append(value)
            }
        } else {
            selected = selected == [value] ? [] : [value]
        }
        questionForm.updateCurrent(selected: selected, custom: answerText)
        persistQuestionDraft()
    }

    private func questionNavigator(_ copy: ConversationCopy) -> some View {
        let count = max(questionForm.questions.count, 1)
        return VStack(spacing: 8) {
            // C06：自由回答用独立输入框，不再复用正文 draft —— 切题 / 提交
            // 不会把用户正在写的正文冲掉。
            TextField(copy.text(.questionAnswerPlaceholder), text: $answerText)
                .textFieldStyle(.roundedBorder)
                .frame(minHeight: 44)
                .onChange(of: answerText) { _, _ in persistQuestionDraft() }
            HStack {
                Button(copy.text(.questionPrevious)) { moveQuestion(.previous) }
                    .disabled(!questionForm.canGoBack || decisionBusy)
                if questionForm.canSkip {
                    Button(copy.text(.questionSkip)) { moveQuestion(.skip) }
                        .disabled(decisionBusy)
                }
                Spacer()
                Text(copy.format(.questionProgress, questionForm.index + 1, count))
                    .font(DLFont.footnote)
                    .foregroundStyle(DLColor.secondaryLabel)
                Spacer()
                Button(questionForm.isLast ? copy.text(.send) : copy.text(.questionNext)) {
                    moveQuestion(questionForm.isLast ? .submit : .next)
                }
                // C06 10.2.2：含未知题型时**不提交**（也不静默丢空数组），
                // 由下方说明告诉用户去电脑上答。
                .disabled(decisionBusy || questionForm.hasUnsupportedQuestion)
            }
            .frame(minHeight: 44)
            // C06 10.2.2：未知题型的用户可见说明——按钮为什么点不动。
            if questionForm.hasUnsupportedQuestion {
                Text(copy.text(.questionUnsupported))
                    .font(DLFont.footnote)
                    .foregroundStyle(DLColor.err)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.horizontal, 16)
        .onAppear { if let activeQuestion { syncQuestionForm(activeQuestion) } }
        .onChange(of: activeQuestion?.questionRpcId) { _, _ in
            if let activeQuestion { syncQuestionForm(activeQuestion) }
        }
    }

    /// 每题草稿存进 `.answer` 槽位，与正文 `.prompt` 分开（C02 要求 2）。
    private func persistQuestionDraft() {
        guard let id = activeQuestion?.questionRpcId else { return }
        questionForm.updateCurrent(custom: answerText)
        drafts.update(questionForm.snapshot(rpcID: id).encoded, kind: .answer)
    }

    private func syncQuestionForm(_ message: RequestMessage) {
        guard let rpcID = message.questionRpcId, questionRPC != rpcID else { return }
        questionRPC = rpcID
        questionForm = QuestionForm(questions: QuestionForm.questions(from: message.questionPayloadJson))
        // 先恢复这一题上次写到一半的回答，再冻结正文草稿（不清空）。
        if let saved = QuestionFormSnapshot.decode(drafts.restored(.answer)) {
            questionForm.restore(saved, rpcID: rpcID)
        }
        answerText = questionForm.currentCustom
        syncQuestionDraft()
    }

    private func moveQuestion(_ action: QuestionNavigation) {
        guard !decisionBusy, let message = activeQuestion, let id = message.questionRpcId else { return }
        syncQuestionForm(message)
        questionForm.updateCurrent(custom: answerText)
        // C06 10.2.2：含未知题型时一律不提交（按钮已禁用，这里再兜一层）。
        if action == .submit, questionForm.hasUnsupportedQuestion {
            decisionNotice = .questionUnsupported
            decisionNoticeArguments = []
            return
        }
        if action == .submit {
            guard let body = questionForm.move(.submit) else { return }
            decisionBusy = true
            decisionNotice = nil
            Task {
                defer { decisionBusy = false }
                do {
                    try await model.serviceQuestion(rpcID: id, answer: body)
                    // 提交成功才清回答草稿。失败时保留，用户重试不用重打。
                    drafts.clear(.answer)
                    answerText = ""
                    decisionPulse += 1
                } catch {
                    // C06 10.2.8：把服务器校验错误定位到具体题，并跳到那一题让用户就地改。
                    let located = locateQuestionValidationError(error, questions: questionForm.questions)
                    if let located, located.isLocalized, questionForm.moveToQuestion(located) {
                        answerText = questionForm.currentCustom
                        decisionNotice =
                            located.questionIndex.map { _ in
                                .questionInvalidIndexed
                            } ?? .questionInvalidGeneric
                        decisionNoticeArguments = located.questionIndex.map { [$0] } ?? []
                    } else {
                        // 定位不到就不猜，退回通用文案。
                        decisionNotice = .questionInvalidGeneric
                        decisionNoticeArguments = []
                    }
                    // 失败时保留草稿，用户改完可直接重试。
                    persistQuestionDraft()
                }
            }
            return
        }
        _ = questionForm.move(action)
        answerText = questionForm.currentCustom
        persistQuestionDraft()
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
        if showDocuments {
            showDocuments = false
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

    /// C06：当前决策是否已被其他设备处理（请求状态已终态）。
    /// 另一设备先处理时，面板显示"已处理"且不给成功触感（方案 §10.1 要求 4）。
    private var isDecisionHandled: Bool {
        guard let decision else { return false }
        switch decision {
        case .approval(let message), .question(let message):
            return isTerminalRequestStatus(message.requestStatus)
        }
    }

    /// C06 10.1.5 / 10.2.8：面板内提示文案。带参数时走 format（如「第 N 题」）。
    private func decisionNoticeText(_ copy: ConversationCopy) -> String? {
        guard let decisionNotice else { return nil }
        guard !decisionNoticeArguments.isEmpty else { return copy.text(decisionNotice) }
        return copy.format(decisionNotice, decisionNoticeArguments)
    }

    /// C06 10.1.6：多个可处理请求时给出「第 N / 共 M 个」的位置。
    /// 只有一个（或没有）请求时返回 nil，不在面板上堆无意义文案。
    private func decisionPositionText(_ copy: ConversationCopy) -> String? {
        guard let decision else { return nil }
        let pending = pendingRequestIDs
        guard pending.count > 1 else { return nil }
        let currentID: String?
        switch decision {
        case .approval(let message): currentID = message.approvalId
        case .question(let message): currentID = message.questionRpcId
        }
        guard let currentID, let offset = pending.firstIndex(of: currentID) else { return nil }
        return copy.format(.decisionPosition, offset + 1, pending.count)
    }

    /// 当前所有**手机上可处理**的请求 id，按处理顺序（与 `pendingPhoneDecision`
    /// 的 `reversed()` 一致：新请求在前）。
    private var pendingRequestIDs: [String] {
        model.status.requests.messages.reversed().compactMap { message -> String? in
            if actionablePhoneApproval(message) { return message.approvalId }
            if actionablePhoneQuestion(message) { return message.questionRpcId }
            return nil
        }
    }

    /// C05：按会话状态给编辑器提示。空闲→"给这个会话发消息"，运行中→"补充说明"。
    /// 不抢焦点，不进发送正文（只是 UILabel 叠加在编辑器上）。
    private func composerPlaceholderText(_ copy: ConversationCopy) -> String {
        switch model.status.kind {
        case .goal, .preview:
            // 目标进行中 / 预览中 → 按"运行中"提示
            copy.text(.composerRunningPrompt)
        default:
            copy.text(.composerIdlePrompt)
        }
    }

    /// 失败文案：未知结果要明说"不自动重发"，别让用户以为没发出去而重复点。
    private func submissionNoticeText(_ copy: ConversationCopy) -> String {
        copy.text(submissionNotice ?? .sendFailedKeepDraft)
    }

    private func send(_ copy: ConversationCopy) async {
        _ = copy
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        let images = attachments
        guard !text.isEmpty || !images.isEmpty, !staticSnapshot else { return }
        if isDangerPermissionCommand(text) {
            confirmFull = true
            return
        }
        // 本地提交锁：在途时重复点击不产生第二次提交（方案 §7 要求 2）。
        guard !submission.busy else { return }

        let snapshot = SubmissionSnapshot(
            revision: draftRevision, text: text, attachmentCount: images.count)
        submission = .submitting(revision: snapshot.revision)
        submissionNotice = nil
        do {
            try await model.serviceSend(snapshot.text, images: images)
            submission = .accepted(revision: snapshot.revision)
            // 自己发完消息 → 按合同恢复跟随（方案 §8 要求 3）。
            showNewMessagesPill = false
            streamCoordinator.scrollToLatest()
            // 关键：只清"提交过且之后没被改动"的那一份。
            // 提交期间用户若又写了新内容（revision 已前进），绝不清空。
            if SubmissionResolver.shouldClear(submitted: snapshot.revision, current: draftRevision) {
                draft = ""
                if SubmissionResolver.shouldClearAttachments(
                    submitted: snapshot.revision, current: draftRevision)
                {
                    attachments = []
                }
                // 正文与引用两个槽位都清掉。
                drafts.clear(.prompt)
                drafts.clear(.reference)
            }
        } catch {
            // 结果未知（超时/断连）与确定失败都不清输入，也**不自动重发**。
            let failure = SubmissionFailure.classify(error)
            submission =
                failure == .outcomeUnknown
                ? .outcomeUnknown(revision: snapshot.revision)
                : .failedBeforeAccept(revision: snapshot.revision, message: "")
            submissionNotice = failure.copyKey
        }
    }

    /// C09：打开会话工作区文件 → 下载，按类型展示；失败给说明不留无效链接。
    private func openFile(_ path: String, copy: ConversationCopy) {
        Task {
            await model.openFile(path: path)
            if model.openedFile != nil { showFilePreview = true }
        }
    }
    private func decide(allow: Bool) async {
        guard !staticSnapshot, !decisionBusy, let decision else { return }
        decisionBusy = true
        decisionNotice = nil
        defer { decisionBusy = false }
        do {
            switch decision {
            case .approval(let message):
                guard let id = message.approvalId else { return }
                try await model.serviceApproval(id: id, outcome: allow ? "allowed-once" : "rejected")
            case .question(let message):
                guard allow, let id = message.questionRpcId else { return }
                syncQuestionForm(message)
                questionForm.updateCurrent(custom: answerText)
                guard let body = questionForm.move(.submit) else { return }
                try await model.serviceQuestion(rpcID: id, answer: body)
                // 提交成功才清。失败保留，用户重试不用重打。
                drafts.clear(.answer)
                answerText = ""
            }
            // C06：另一设备已处理时不给成功触感（方案 §10.1 要求 4）
            if !isDecisionHandled {
                decisionPulse += 1
            }
        } catch {
            decisionNotice = .decisionFailed
        }
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
                },
                onFiles: {
                    sheet = nil
                    showDocuments = true
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
                onPause: { Task { await model.servicePauseGoal() } },
                pauseTitle: model.status.goal?.phase == .paused ? copy.text(.paletteResume) : copy.text(.palettePause),
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

    private func acceptFiles(_ urls: [URL]) {
        let files = urls.map { url -> Result<PickedPromptFile, PromptFileReadFailure> in
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            return readPromptFile(at: url)
        }
        let accepted = acceptPromptFiles(files, existing: attachments)
        attachments = accepted.images
        attachmentNotice = accepted.rejection.map(attachmentNoticeKey)
    }

    private func pickSlash(_ trigger: String, copy: ConversationCopy) {
        let entries = paletteEntries(
            title: { paletteCopy($0, copy: copy) }, detail: { paletteCopy($0, copy: copy) })
        guard let command = entries.first(where: { $0.command.trigger == trigger })?.command else { return }
        switch resolvedPick(command) {
        case .insert(let text):
            draftRevision += 1
            draft = text
        case .submit(let text):
            draftRevision += 1
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
                    Button(copy.text(.menuFiles)) {
                        model.loadFiles(path: model.filesPath)
                        showFiles = true
                    }
                    Button(copy.text(.menuTrajectory)) { showTrajectory = true }
                    Button(copy.text(.menuAgents)) { showAgents = true }
                    Button(copy.text(.menuUsage)) { sheet = .usage }
                    Button(copy.text(.menuPreview)) {
                        model.loadPreviews()
                        showPreview = true
                    }
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
    /// C08：真实改动数据（从 ConversationModel 取），不再是空集合。
    var model: ConversationModel
    /// C08：把改动引用并入输入区。只预填，**不自动发送**。
    var onAsk: (String) -> Void

    private var files: [ChangedFile] { model.changes?.files ?? [] }
    private var turn: Int { model.changes?.turn ?? (model.changesSeq ?? 0) }

    func body(content: Content) -> some View {
        let page = ChangesPage(
            files: files,
            turn: max(1, turn),
            canPrevious: model.changesPreviousSeq != nil,
            canNext: model.changesNextSeq != nil,
            copy: copy,
            onAsk: { onAsk(model.changesAskReference()) },
            onPrevious: { model.viewAdjacentChanges(forward: false) },
            onNext: { model.viewAdjacentChanges(forward: true) },
            onAskFile: { onAsk(model.changesAskReference(index: $0)) },
            loading: model.changesLoading,
            error: model.changesError,
            onRetry: { [model] in if let seq = model.changesSeq { model.viewChanges(seq: seq) } },
            onOpenDiff: { [model] index in
                guard let seq = model.changesSeq else { return }
                model.loadFileDiff(seq: seq, index: index)
            },
            diff: model.fileDiffs,
            diffUnavailable: model.unavailableDiffs)
        if regular {
            content.inspector(isPresented: $presented) {
                page
                    .inspectorColumnWidth(min: 280, ideal: 360, max: 480)
            }
        } else {
            content.navigationDestination(isPresented: $presented) {
                page
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

private func attachmentNoticeKey(_ rejection: PromptAttachmentRejection) -> ChatText {
    switch rejection {
    case .unsupported: .attachUnsupported
    case .tooLarge: .attachTooLarge
    case .limit: .attachLimit
    case .unreadable: .attachUnreadable
    }
}
