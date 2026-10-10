import DLCore
import DLModels
import DLNet
import DLSecurity
import DLUI
import SwiftUI

struct InboxFlowView: View {
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.composerDraftStore) private var draftStore
    @State private var model: InboxModel
    var onMissing: () -> Void
    var onSwitch: (String) -> Void

    init(
        hostID: String,
        onMissing: @escaping () -> Void,
        onSwitch: @escaping (String) -> Void,
        model: InboxModel? = nil
    ) {
        self.onMissing = onMissing
        self.onSwitch = onSwitch
        if let model {
            _model = State(initialValue: model)
        } else {
            let live = InboxModel(
                hostID: hostID,
                service: InboxLiveService(
                    hostID: hostID,
                    store: PerformanceLaunchFixture.isRequested
                        || PerformanceLaunchFixture.unsignedStorage != nil
                        ? PerformanceLaunchFixture.hostStore() : HostStore(),
                    backgroundTasks: SystemBackgroundTasks()),
                cache: PerformanceLaunchFixture.isRequested
                    ? InboxDiskCache(directory: PerformanceLaunchFixture.inboxDirectory())
                    : InboxDiskCache())
            // Lock-screen Live Activity follows the Settings 7.4 switch (off by default).
            live.liveActivity = InboxLiveActivitySync(
                controller: LiveActivityController(
                    adapter: ActivityKitLiveActivityAdapter(),
                    isEnabled: { UserDefaults.standard.bool(forKey: "settings.liveActivity") }))
            _model = State(initialValue: live)
        }
    }

    var body: some View {
        InboxPage(model: model)
            .onAppear {
                model.onSwitch = onSwitch
                model.discardDrafts = { [draftStore] hostID, sessionID in
                    draftStore.removeAll(hostID: hostID, sessionID: sessionID)
                }
            }
            .onChange(of: model.missingHost) { _, missing in
                if missing { onMissing() }
            }
            .onReceive(NotificationCenter.default.publisher(for: .deepLinksOpenPush)) { notification in
                guard let info = notification.userInfo,
                    let request = PushPayloadReader.openRequest(in: info)
                else { return }
                Task { await model.openPush(deviceID: request.deviceID, sessionID: request.sessionID) }
            }
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

struct InboxPage: View {
    @Bindable var model: InboxModel
    @Environment(\.locale) private var locale
    @Environment(\.horizontalSizeClass) private var sizeClass
    /// Inbox snapshots only. Production still calls `start()`.
    var staticSnapshot = false
    /// Wide snapshots only. Production builds a live conversation for `selectedSessionID`.
    var wideSnapshotConversation: ConversationModel? = nil
    var wideSnapshotChanges = false
    @State private var columnVisibility = NavigationSplitViewVisibility.doubleColumn
    @State private var searchPresented = false

    var body: some View {
        let copy = InboxCopy(locale: locale)
        if sizeClass == .regular {
            wide(copy)
        } else if staticSnapshot {
            // No `.task` / `start()`, no search controller, no refresh, no alert, no animation.
            // Draw the presentation the fixture already installed.
            // `.borderedProminent` in a toolbar snapshots as a fully transparent image.
            NavigationStack(path: $model.path) {
                screen(copy)
                    .navigationDestination(for: InboxDestination.self) { destination in
                        InboxDestinationPage(destination: destination, model: model)
                    }
            }
            .tint(DLColor.accent)
            .transaction { $0.disablesAnimations = true }
        } else {
            NavigationStack(path: $model.path) {
                screen(copy)
                    .searchable(
                        text: $model.query,
                        tokens: $model.tokens,
                        suggestedTokens: $model.workspaceSuggestions,
                        prompt: Text(copy.text(.searchPrompt))
                    ) { token in
                        Text(token.name)
                    }
                    .searchSuggestions { suggestions(copy) }
                    .onSubmit(of: .search) { model.submitSearch() }
                    .refreshable { await model.refresh() }
                    .navigationDestination(for: InboxDestination.self) { destination in
                        InboxDestinationPage(destination: destination, model: model)
                    }
            }
            .tint(DLColor.accent)
            .task { await model.start() }
            .onDisappear { Task { await model.stop() } }
            .onChange(of: model.query) { _, _ in Task { await model.applyQuery() } }
            .onChange(of: model.tokens) { _, tokens in
                if tokens.count > 1 {
                    model.tokens = Array(tokens.suffix(1))
                    return
                }
                model.persistWorkspace()
                model.syncSuggestions()
                Task { await model.applyQuery() }
            }
            .alert(copy.text(.rename), isPresented: renamePresented) {
                TextField(copy.text(.rename), text: $model.renameDraft)
                Button(copy.text(.cancel), role: .cancel) { model.renameTarget = nil }
                Button(copy.text(.rename)) { Task { await model.commitRename() } }
            } message: {
                Text(copy.text(.renameHint))
            }
            .confirmationDialog(copy.text(.deleteTitle), isPresented: deletePresented, titleVisibility: .visible) {
                Button(copy.text(.delete), role: .destructive) { Task { await model.commitDelete() } }
                Button(copy.text(.cancel), role: .cancel) { model.deleteTarget = nil }
            } message: {
                Text(copy.format(.deleteMessage, deleteName(copy)))
            }
            .confirmationDialog(
                copy.text(.deleteWorkspaceTitle), isPresented: workspaceDeletePresented, titleVisibility: .visible
            ) {
                Button(copy.text(.deleteWorkspace), role: .destructive) {
                    if let path = model.deleteWorkspacePath { Task { await model.deleteWorkspace(path) } }
                }
                Button(copy.text(.cancel), role: .cancel) { model.deleteWorkspacePath = nil }
            } message: {
                Text(copy.format(.deleteWorkspaceMessage, model.deleteWorkspacePath ?? ""))
            }
            .sensoryFeedback(.success, trigger: model.approvalTick)
            .onOpenURL { model.receiveShare(url: $0, store: ShareGroupStore.live()) }
            .sheet(isPresented: sharePresented) {
                if let record = model.pendingShare {
                    SharePickerSheet(
                        record: record, recents: model.shareRecents, copy: ShareCopy(locale: locale),
                        onPick: { model.acceptShare($0, store: ShareGroupStore.live()) },
                        onCancel: { model.cancelShare(store: ShareGroupStore.live()) })
                }
            }
        }
    }

    private var sharePresented: Binding<Bool> {
        Binding(
            get: { model.pendingShare != nil },
            set: { shown in if !shown { model.cancelShare(store: ShareGroupStore.live()) } })
    }

    private func wide(_ copy: InboxCopy) -> some View {
        let split = NavigationSplitView(columnVisibility: $columnVisibility) {
            wideSidebar(copy)
                .navigationSplitViewColumnWidth(min: 180, ideal: 260, max: 360)
        } detail: {
            NavigationStack(path: $model.path) {
                wideDetail(copy)
                    .navigationDestination(for: InboxDestination.self) { destination in
                        InboxDestinationPage(destination: destination, model: model)
                    }
            }
        }
        .navigationSplitViewStyle(.balanced)
        .tint(DLColor.accent)
        return Group {
            if staticSnapshot {
                split
                    .toolbarBackground(.visible, for: .navigationBar)
                    .toolbarBackground(Color(uiColor: .systemBackground), for: .navigationBar)
                    .transaction { $0.disablesAnimations = true }
            } else {
                split
                    .task { await model.start() }
                    .onDisappear { Task { await model.stop() } }
                    .onChange(of: model.query) { _, _ in Task { await model.applyQuery() } }
                    .onChange(of: model.tokens) { _, tokens in
                        if tokens.count > 1 {
                            model.tokens = Array(tokens.suffix(1))
                            return
                        }
                        model.persistWorkspace()
                        model.syncSuggestions()
                        Task { await model.applyQuery() }
                    }
                    .alert(copy.text(.rename), isPresented: renamePresented) {
                        TextField(copy.text(.rename), text: $model.renameDraft)
                        Button(copy.text(.cancel), role: .cancel) { model.renameTarget = nil }
                        Button(copy.text(.rename)) { Task { await model.commitRename() } }
                    } message: {
                        Text(copy.text(.renameHint))
                    }
                    .confirmationDialog(
                        copy.text(.deleteTitle), isPresented: deletePresented, titleVisibility: .visible
                    ) {
                        Button(copy.text(.delete), role: .destructive) { Task { await model.commitDelete() } }
                        Button(copy.text(.cancel), role: .cancel) { model.deleteTarget = nil }
                    } message: {
                        Text(copy.format(.deleteMessage, deleteName(copy)))
                    }
                    .sensoryFeedback(.success, trigger: model.approvalTick)
                    .background { wideShortcuts(copy) }
                    .onKeyPress(.escape) { wideEscape() }
            }
        }
    }

    @ViewBuilder private func wideSidebar(_ copy: InboxCopy) -> some View {
        if staticSnapshot {
            screen(copy)
        } else {
            screen(copy)
                .searchable(
                    text: $model.query,
                    tokens: $model.tokens,
                    suggestedTokens: $model.workspaceSuggestions,
                    isPresented: $searchPresented,
                    prompt: Text(copy.text(.searchPrompt))
                ) { token in
                    Text(token.name)
                }
                .searchSuggestions { suggestions(copy) }
                .onSubmit(of: .search) { model.submitSearch() }
                .refreshable { await model.refresh() }
        }
    }

    @ViewBuilder private func wideDetail(_ copy: InboxCopy) -> some View {
        if let id = model.selectedSessionID {
            if let conversation = wideSnapshotConversation {
                ConversationPage(
                    model: conversation, staticSnapshot: true, showsStatusSlot: false, showsComposer: false,
                    presentChanges: wideSnapshotChanges
                )
                .id(id)
            } else {
                ConversationFlowView(
                    hostID: model.hostID, sessionID: id, seed: conversationSeed(sessionID: id, model: model),
                    sessions: model.sessions
                )
                .id(id)
            }
        } else {
            DLEmptyState(title: copy.text(.pickSession), systemImage: "bubble.left.and.bubble.right")
        }
    }

    private func wideShortcuts(_ copy: InboxCopy) -> some View {
        Group {
            Button(copy.text(.newTask)) { model.openNewTask() }
                .keyboardShortcut("n", modifiers: .command)
            Button(copy.text(.searchPrompt)) { searchPresented = true }
                .keyboardShortcut("f", modifiers: .command)
        }
        .opacity(0)
        .accessibilityHidden(true)
        .allowsHitTesting(false)
    }

    private func wideEscape() -> KeyPress.Result {
        if !model.path.isEmpty {
            model.path.removeLast()
            return .handled
        }
        if searchPresented {
            searchPresented = false
            return .handled
        }
        if model.renameTarget != nil {
            model.renameTarget = nil
            return .handled
        }
        if model.deleteTarget != nil {
            model.deleteTarget = nil
            return .handled
        }
        return .ignored
    }

    private func screen(_ copy: InboxCopy) -> some View {
        inbox(copy)
            .navigationTitle(model.displayName)
            .navigationSubtitle(copy.subtitle(link: model.link))
            // 设计稿 2.1：手机上是大标题 + 电脑状态副标题；iPad 侧栏仍用行内标题。
            // 手机截图的大标题由 `snapshotHeader` 画在内容里，系统栏保持行内，免得顶部多留一段空白。
            .navigationBarTitleDisplayMode(sizeClass == .regular || staticSnapshot ? .inline : .large)
            .toolbar { toolbar(copy) }
            // 手机截图里系统导航栏的文字取色不稳（浅色下是白字，深色下又会和替身标题重复），
            // 截图直接隐藏系统栏，只留内容里的替身标题。
            .toolbar(snapshotsPhoneChrome ? .hidden : .automatic, for: .navigationBar)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if snapshotsPhoneChrome { snapshotBottomBar(copy) }
            }
    }

    /// 截图里系统导航栏和底部玻璃工具栏画不出来（整页截图拿到的是空白）。
    /// 手机截图在内容里按同样的层级画一份：大标题 + 副标题、底部搜索框 + 品牌色「新任务」。
    /// 生产路径始终用系统大标题和 `.bottomBar` 工具栏。
    private var snapshotsPhoneChrome: Bool { staticSnapshot && sizeClass != .regular }

    private func snapshotHeader(_ copy: InboxCopy) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(model.displayName)
                .font(.largeTitle.bold())
                .foregroundStyle(DLColor.label)
            Text(copy.subtitle(link: model.link))
                .font(.subheadline)
                .foregroundStyle(DLColor.secondaryLabel)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 8, trailing: 16))
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
    }

    private func snapshotBottomBar(_ copy: InboxCopy) -> some View {
        let fill = Color(uiColor: .secondarySystemBackground)
        return HStack(spacing: 12) {
            Image(systemName: "line.3.horizontal.decrease")
                .frame(width: 44, height: 44)
                .background(fill, in: Circle())
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                Text(model.query.isEmpty ? copy.text(.searchPrompt) : model.query)
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .foregroundStyle(DLColor.secondaryLabel)
            .padding(.horizontal, 14)
            .frame(minHeight: 44)
            .background(fill, in: Capsule())
            Image(systemName: "square.and.pencil")
                .font(.headline)
                .foregroundStyle(.white)
                .frame(width: 44, height: 44)
                .background(DLColor.brandFill, in: Circle())
                .accessibilityLabel(copy.text(.newTask))
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(DLColor.background)
    }

    private var renamePresented: Binding<Bool> {
        Binding(get: { model.renameTarget != nil }, set: { if !$0 { model.renameTarget = nil } })
    }

    private var deletePresented: Binding<Bool> {
        Binding(get: { model.deleteTarget != nil }, set: { if !$0 { model.deleteTarget = nil } })
    }

    private var workspaceDeletePresented: Binding<Bool> {
        Binding(get: { model.deleteWorkspacePath != nil }, set: { if !$0 { model.deleteWorkspacePath = nil } })
    }

    private func deleteName(_ copy: InboxCopy) -> String {
        let title = inboxDisplayTitle(model.deleteTarget?.title)
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? copy.text(.untitled) : trimmed
    }

    @ViewBuilder private func inbox(_ copy: InboxCopy) -> some View {
        // C07 §11.2 R11: cancelling a search returns the user to where they were.
        // The model records the anchor when the query becomes non-empty and bumps
        // `searchRestoreToken` when it empties again; this scrolls once per bump.
        ScrollViewReader { proxy in
            Group {
                if sizeClass == .regular {
                    List(selection: $model.selectedSessionID) { inboxRows(copy) }
                        .listStyle(.plain)
                } else {
                    List { inboxRows(copy) }
                        .listStyle(.plain)
                }
            }
            .onChange(of: model.searchRestoreToken) { _, token in
                guard token != nil, let anchor = model.searchReturnAnchor else { return }
                // One runloop turn so the unfiltered rows exist before scrolling.
                Task { @MainActor in
                    withAnimation(nil) { proxy.scrollTo(anchor, anchor: .top) }
                }
            }
        }
    }

    @ViewBuilder private func inboxRows(_ copy: InboxCopy) -> some View {
        if snapshotsPhoneChrome {
            Section { snapshotHeader(copy) }
        }
        if let banner = banner(copy) {
            Section {
                VStack(alignment: .leading, spacing: 12) {
                    DLBanner(banner.text, systemImage: banner.icon, iconIsError: banner.iconIsError)
                    HStack(spacing: 12) {
                        Button(copy.text(.retry)) { Task { await model.refresh() } }
                            .buttonStyle(.bordered)
                        Button(copy.text(.diagnostics)) { model.path.append(.diagnostics) }
                            .buttonStyle(.bordered)
                    }
                }
                .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }
        }
        switch model.presentation {
        case .loading:
            DLEmptyState(title: copy.text(.loading), systemImage: "hourglass")
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
        case .starters:
            empty(copy, title: copy.text(.emptyTitle), message: copy.text(.emptyHint), symbol: "tray")
            Section(copy.text(.startFrom)) {
                starter(copy, .starterOrganize)
                starter(copy, .starterTest)
                starter(copy, .starterDiff)
            }
        case .workspaceEmpty:
            empty(
                copy, title: copy.text(.workspaceEmptyTitle), message: copy.text(.workspaceEmptyHint),
                symbol: "folder")
            Button(copy.text(.showAll)) { model.setWorkspace(nil) }
        case .offlineEmpty(let reason):
            // C14：错误不是空状态，且不同原因给不同说明。
            // 被拒（未授权/证书不符）重试没有意义，要引导重新配对；
            // 连不上则说明这是最后一次保存的内容，恢复后会自动重连。
            switch reason {
            case .rejected:
                empty(
                    copy, title: copy.text(.loadFailedTitle),
                    message: copy.text(.loadFailedHint),
                    symbol: "exclamationmark.triangle")
            case .unreachable:
                empty(
                    copy, title: copy.format(.offlineTitle, model.displayName),
                    message: copy.text(.offlineHintPlain), symbol: "wifi.slash")
            }
        case .search(let groups, let degraded, let failed):
            searchResults(copy, groups: groups, degraded: degraded, failed: failed)
        case .folders(let folders):
            let pinned = folders.flatMap(\.sessions).filter { $0.awaitingInput == true }
            if !pinned.isEmpty {
                Section(copy.text(.filterAwaiting)) {
                    ForEach(pinned, id: \.sessionId) { session in
                        sessionRow(session, copy: copy, needle: "", keepsWorkspace: true)
                            // Anchor for scroll restore after cancelling a search.
                            .id(session.sessionId)
                    }
                }
            }
            ForEach(folders, id: \.key) { folder in
                let rest = folder.sessions.filter { $0.awaitingInput != true }
                let open = !model.collapsedFolders.contains(folder.key)
                let shown = model.expandedPreviews.contains(folder.key) ? rest : Array(rest.prefix(3))
                Section {
                    if open {
                        ForEach(shown, id: \.sessionId) { session in
                            sessionRow(session, copy: copy, needle: "")
                                .padding(.leading, 16)
                                // Anchor for scroll restore after cancelling a search.
                                .id(session.sessionId)
                        }
                        if rest.count > 3 {
                            Button(copy.format(shown.count == rest.count ? .collapseAll : .showAllCount, rest.count)) {
                                model.togglePreview(folder.key)
                            }
                        }
                        if let path = folder.path {
                            Button(copy.text(.newHere)) { model.openNewTask(workspace: path) }
                                .disabled(!model.actionsEnabled)
                        }
                    }
                } header: {
                    Button(folderTitle(folder, copy: copy)) { model.toggleFolder(folder.key) }
                        .contextMenu {
                            if let path = folder.path {
                                Button(copy.text(.newHere)) { model.openNewTask(workspace: path) }
                                Button(copy.text(.deleteWorkspace), role: .destructive) {
                                    model.deleteWorkspacePath = path
                                }
                            }
                        }
                }
            }
        }
    }

    private func folderTitle(_ folder: InboxWorkspaceFolder, copy: InboxCopy) -> String {
        let labels = inboxWorkspaceLabels(model.folderPaths)
        let name = folder.path.flatMap { labels[$0] } ?? copy.text(.ungrouped)
        let counts = [folder.awaitingCount, folder.runningCount].filter { $0 > 0 }.map(String.init)
        return counts.isEmpty ? name : name + " " + counts.joined(separator: " · ")
    }

    private func empty(_ copy: InboxCopy, title: String, message: String?, symbol: String) -> some View {
        DLEmptyState(title: title, systemImage: symbol, message: message)
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
    }

    private func starter(_ copy: InboxCopy, _ key: InboxText) -> some View {
        Button(copy.text(key)) { model.openNewTask(copy.text(key)) }
    }

    @ViewBuilder private func searchResults(
        _ copy: InboxCopy, groups: InboxSearchGroups, degraded: Bool, failed: Bool
    ) -> some View {
        let needle = model.query.trimmingCharacters(in: .whitespacesAndNewlines)
        if failed {
            DLEmptyState(title: copy.text(.searchFailed), systemImage: "magnifyingglass")
                .listRowBackground(Color.clear)
        } else if groups.titleMatches.isEmpty && groups.contentMatches.isEmpty {
            DLEmptyState(
                title: copy.text(.searchEmpty), systemImage: "magnifyingglass",
                message: degraded ? copy.text(.searchDegraded) : nil
            )
            .listRowBackground(Color.clear)
        } else {
            if degraded {
                Text(copy.text(.searchDegraded))
                    .font(DLFont.footnote)
                    .foregroundStyle(DLColor.secondaryLabel)
                    .listRowBackground(Color.clear)
            }
            if !groups.titleMatches.isEmpty {
                Section(copy.text(.titleMatches)) {
                    ForEach(groups.titleMatches, id: \.sessionId) { session in
                        sessionRow(session, copy: copy, needle: needle)
                    }
                }
            }
            if !groups.contentMatches.isEmpty {
                Section(copy.text(.contentMatches)) {
                    ForEach(groups.contentMatches, id: \.session.sessionId) { hit in
                        sessionRow(hit.session, copy: copy, needle: needle, snippet: hit.snippet)
                    }
                }
            }
        }
        if model.searching {
            Text(copy.text(.searching))
                .font(DLFont.footnote)
                .foregroundStyle(DLColor.secondaryLabel)
                .listRowBackground(Color.clear)
        }
    }

    private func sessionRow(
        _ session: SessionSummary, copy: InboxCopy, needle: String, snippet: String? = nil,
        keepsWorkspace: Bool = false
    ) -> some View {
        let offline = model.link == .offline
        let content = inboxRowContent(session: session, action: model.phoneAction, offline: offline)
        let title = displayTitle(session, copy: copy)
        let compact = snippet == nil && content.pending == .none
        // 设计稿 2.1：普通会话行也要有时间和当前步骤（「正在运行 … · 第 12 步」）。
        // 文件夹分组里工作区名已在组头，行内只留子任务数和状态。
        let meta =
            keepsWorkspace || !compact
            ? copy.meta(workspace: content.workspace, subagents: content.subagentCount, status: content.status)
            : copy.meta(workspace: nil, subagents: content.subagentCount, status: content.status)
        let preview = snippet ?? content.preview.map { copy.preview($0) }
        return VStack(alignment: .leading, spacing: 8) {
            DLInboxRow(
                mailMeta: meta,
                title: title,
                time: copy.time(inboxTime(session.updatedAt, now: model.now, calendar: model.calendar)),
                preview: preview,
                command: compact ? nil : content.command,
                dot: dot(content.dot),
                needle: needle,
                isEnabled: true
            )
            .contentShape(Rectangle())
            .onTapGesture {
                if sizeClass == .regular, let id = session.sessionId {
                    model.path.removeAll()
                    model.selectedSessionID = id
                } else {
                    model.open(session)
                }
            }
            actions(content, session: session, copy: copy)
        }
        .contextMenu { inboxSessionActions(session, model: model, copy: copy) }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if content.allowsSwipe {
                Button(copy.text(.delete), role: .destructive) { model.askDelete(session) }
            }
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private func actions(_ content: InboxRowContent, session: SessionSummary, copy: InboxCopy) -> some View
    {
        // 设计稿 2.1：首页的品牌实心按钮留给「新任务」，所以「允许一次」是浅色着色按钮，
        // 「拒绝 / 回答」是灰色按钮；按钮靠右排。
        if content.pending == .approval {
            HStack(spacing: 8) {
                Spacer(minLength: 0)
                Button(copy.text(.reject)) { Task { await model.decide(allow: false) } }
                    .buttonStyle(.bordered)
                    .tint(DLColor.label)
                Button(copy.text(.allowOnce)) { Task { await model.decide(allow: true) } }
                    .buttonStyle(.bordered)
                    .tint(DLColor.accent)
            }
            .disabled(!model.actionsEnabled)
            .accessibilityHint(model.actionsEnabled ? "" : copy.text(.approveBlocked))
        } else if content.pending == .question {
            HStack {
                Spacer(minLength: 0)
                Button(copy.text(.answer)) { model.open(session) }
                    .buttonStyle(.bordered)
                    .tint(DLColor.label)
                    .disabled(!model.actionsEnabled)
            }
        }
    }

    private func dot(_ kind: InboxDotKind?) -> DLInboxDot? {
        switch kind {
        case .wait: .wait
        case .accent: .accent
        case nil: nil
        }
    }

    private func displayTitle(_ session: SessionSummary, copy: InboxCopy) -> String {
        let title = inboxDisplayTitle(session.title).trimmingCharacters(in: .whitespacesAndNewlines)
        return title.isEmpty ? copy.text(.untitled) : title
    }

    private func banner(_ copy: InboxCopy) -> (text: String, icon: String, iconIsError: Bool)? {
        if model.notice == .unauthorized {
            return (copy.text(.unauthorized), "exclamationmark.triangle", true)
        }
        if model.notice == .certificate {
            return (copy.text(.certificate), "exclamationmark.triangle", true)
        }
        if model.link == .offline {
            return (offlineText(copy), "wifi.slash", true)
        }
        if let notice = model.notice {
            return (actionText(notice, copy), "exclamationmark.circle", false)
        }
        return nil
    }

    private func offlineText(_ copy: InboxCopy) -> String {
        let title = copy.format(.offlineTitle, model.displayName)
        let hint: String
        if let date = model.lastOnlineAt {
            let millis = Int(date.timeIntervalSince1970 * 1000)
            let label = copy.time(inboxTime(millis, now: model.now, calendar: model.calendar))
            hint = label.isEmpty ? copy.text(.offlineHintPlain) : copy.format(.offlineHint, label)
        } else {
            hint = copy.text(.offlineHintPlain)
        }
        var text = title + "\n" + hint
        if case .approval = model.phoneAction {
            text += "\n" + copy.text(.approveBlocked)
        }
        return text
    }

    private func actionText(_ notice: InboxNotice, _ copy: InboxCopy) -> String {
        switch notice {
        case .unauthorized: copy.text(.unauthorized)
        case .certificate: copy.text(.certificate)
        case .load: copy.format(.offlineTitle, model.displayName)
        case .archive: copy.text(.archiveFailed)
        case .fork: copy.text(.forkFailed)
        case .rename: copy.text(.renameFailed)
        case .approval: copy.text(.approvalFailed)
        case .search: copy.text(.searchFailed)
        case .delete: copy.text(.deleteFailed)
        case .pushMissing: copy.text(.pushMissing)
        }
    }

    @ViewBuilder private func titleMenu(_ copy: InboxCopy) -> some View {
        Section(copy.text(.computers)) {
            ForEach(model.computerRows(copy: copy)) { row in
                Button {
                    model.selectComputer(row.id)
                } label: {
                    if row.current {
                        Label(row.title, systemImage: "checkmark")
                    } else {
                        Text(row.title)
                    }
                }
            }
        }
    }

    @ToolbarContentBuilder private func toolbar(_ copy: InboxCopy) -> some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            Menu {
                titleMenu(copy)
                Button(copy.text(.settings)) { model.path.append(.settings) }
                Button(copy.text(.archived)) { model.path.append(.archived) }
            } label: {
                Image(systemName: "ellipsis")
            }
            .accessibilityLabel(copy.text(.more))
        }
        // 设计稿 2.1：iOS 26 邮件式底部工具栏——筛选 · 玻璃搜索框 · 品牌色「新任务」。
        // 手机截图里由 `snapshotBottomBar` 代画（系统工具栏截不出来）。
        if !snapshotsPhoneChrome {
            ToolbarItem(placement: .bottomBar) {
                Menu {
                    Picker(copy.text(.filter), selection: $model.filter) {
                        ForEach(InboxListFilter.allCases, id: \.self) { item in
                            Text(copy.filter(item)).tag(item)
                        }
                    }
                } label: {
                    Label(copy.text(.filter), systemImage: "line.3.horizontal.decrease")
                }
            }
            DefaultToolbarItem(kind: .search, placement: .bottomBar)
            ToolbarItem(placement: .bottomBar) {
                newTask(copy)
            }
        }
    }

    @ViewBuilder private func newTask(_ copy: InboxCopy) -> some View {
        let button = Button {
            model.openNewTask()
        } label: {
            Label(copy.text(.newTask), systemImage: "square.and.pencil")
        }
        .disabled(!model.actionsEnabled)
        if staticSnapshot {
            button.buttonStyle(.plain).tint(DLColor.brandFill)
        } else {
            button.buttonStyle(.borderedProminent).tint(DLColor.brandFill)
        }
    }

    @ViewBuilder private func suggestions(_ copy: InboxCopy) -> some View {
        if model.query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !model.recentSearches.isEmpty {
            Section(copy.text(.searchRecent)) {
                ForEach(model.recentSearches, id: \.self) { item in
                    Text(item).searchCompletion(item)
                }
            }
        }
    }
}

@ViewBuilder func inboxSessionActions(_ session: SessionSummary, model: InboxModel, copy: InboxCopy) -> some View {
    Button(copy.text(.delete), role: .destructive) { model.askDelete(session) }
}

struct InboxHomeAction: Equatable, Identifiable, Sendable {
    var id: String
    var title: String
    var enabled: Bool
    var confirmsDelete = false
}

struct InboxHomeEntry: Equatable, Identifiable, Sendable {
    var id: String
    var title: String
    var workspace: String?
    var compact: Bool
    var actions: [InboxHomeAction]
}

struct InboxHomeSection: Equatable, Identifiable, Sendable {
    var id: String
    var title: String
    var entries: [InboxHomeEntry]
    var toggle: String?
    var create: String?
    var createEnabled: Bool
    var delete: String?
}

struct InboxHomeSurface: Equatable, Sendable {
    var more: [String]
    var sections: [InboxHomeSection]
    var search: [InboxHomeEntry]
    var deleteSession: String?
    var deleteWorkspace: String?
}

extension InboxPage {
    func homeSurface(copy: InboxCopy) -> InboxHomeSurface {
        InboxHomeSurface(
            more: [copy.text(.settings), copy.text(.archived)],
            sections: homeSections(copy),
            search: homeSearch(copy),
            deleteSession: model.deleteTarget.flatMap { displayTitle($0, copy: copy) },
            deleteWorkspace: model.deleteWorkspacePath
        )
    }

    private func homeSections(_ copy: InboxCopy) -> [InboxHomeSection] {
        guard case .folders(let folders) = model.presentation else { return [] }
        var sections: [InboxHomeSection] = []
        let pinned = folders.flatMap { $0.sessions }.filter { $0.awaitingInput == true }
        if !pinned.isEmpty {
            sections.append(
                InboxHomeSection(
                    id: "pinned", title: copy.text(.filterAwaiting),
                    entries: pinned.map { homeEntry($0, copy: copy, keepsWorkspace: true) },
                    toggle: nil, create: nil, createEnabled: false, delete: nil))
        }
        for folder in folders {
            let rest = folder.sessions.filter { $0.awaitingInput != true }
            let open = !model.collapsedFolders.contains(folder.key)
            let shown = model.expandedPreviews.contains(folder.key) ? rest : Array(rest.prefix(3))
            let toggle =
                rest.count > 3
                ? copy.format(shown.count == rest.count ? .collapseAll : .showAllCount, rest.count)
                : nil
            sections.append(
                InboxHomeSection(
                    id: folder.key, title: folderTitle(folder, copy: copy),
                    entries: open ? shown.map { homeEntry($0, copy: copy, keepsWorkspace: false) } : [],
                    toggle: open ? toggle : nil,
                    create: open ? folder.path.map { _ in copy.text(.newHere) } : nil,
                    createEnabled: model.actionsEnabled,
                    delete: folder.path.map { _ in copy.text(.deleteWorkspace) }))
        }
        return sections
    }

    private func homeSearch(_ copy: InboxCopy) -> [InboxHomeEntry] {
        guard case .search(let groups, _, _) = model.presentation else { return [] }
        return groups.titleMatches.map { homeEntry($0, copy: copy, keepsWorkspace: false) }
            + groups.contentMatches.map {
                homeEntry($0.session, copy: copy, keepsWorkspace: false, snippet: $0.snippet)
            }
    }

    private func homeEntry(
        _ session: SessionSummary, copy: InboxCopy, keepsWorkspace: Bool, snippet: String? = nil
    ) -> InboxHomeEntry {
        let content = inboxRowContent(session: session, action: model.phoneAction, offline: model.link == .offline)
        let compact = snippet == nil && content.pending == .none
        var actions: [InboxHomeAction] = []
        if content.pending == .approval {
            actions = [
                InboxHomeAction(id: "reject", title: copy.text(.reject), enabled: model.actionsEnabled),
                InboxHomeAction(id: "allow", title: copy.text(.allowOnce), enabled: model.actionsEnabled),
            ]
        } else if content.pending == .question {
            actions = [InboxHomeAction(id: "answer", title: copy.text(.answer), enabled: model.actionsEnabled)]
        }
        actions.append(InboxHomeAction(id: "delete", title: copy.text(.delete), enabled: true, confirmsDelete: true))
        return InboxHomeEntry(
            id: session.sessionId ?? UUID().uuidString,
            title: displayTitle(session, copy: copy),
            workspace: keepsWorkspace || !compact ? content.workspace : nil,
            compact: compact,
            actions: actions)
    }
}
struct InboxDestinationPage: View {
    var destination: InboxDestination
    var model: InboxModel
    @Environment(\.locale) private var locale
    /// 解绑电脑时要一并清掉该主机的草稿（C02 要求 9），所以设置页需要同一个仓库实例。
    @Environment(\.composerDraftStore) private var draftStore

    var body: some View {
        let copy = InboxCopy(locale: locale)
        switch destination {
        case .archived:
            InboxArchivedPage(model: model)
        case .session(let id):
            ConversationFlowView(
                hostID: model.hostID, sessionID: id, seed: conversationSeed(sessionID: id, model: model),
                sessions: model.sessions,
                sharePrefill: model.takeSharePrefill(for: .session(id)))
        case .settings:
            SettingsHomePage(
                computerName: model.computerName.isEmpty ? model.hostID : model.computerName,
                // C10 要求 3：显示**实际连上的地址**。以前这里是 hostID（内部标识），
                // 用户看到的是一串无意义字符。没连上时留空，由设置页显示「未知」而不是编造。
                computerAddress: model.hostAddress ?? "",
                online: model.link.isOnline,
                route: model.settingsRoute,
                // C10 要求 1：生产依赖根必须装配 account，否则 7.2/7.3（电脑信息、
                // 重命名、解绑、诊断）整块都不工作 —— 之前这里没有 `account:`，
                // 详情页拿不到服务，`loadAccount` 直接 return。
                account: SettingsAccountService.live(hostID: model.hostID, draftStore: draftStore),
                models: SettingsModelsModel(service: SettingsModelsLiveService(hostID: model.hostID)),
                push: PushSettingsRegistration(
                    hostID: model.hostID, pushVersion: model.pushVersion, pairedDeviceID: model.pairedDeviceID))
        case .computer:
            later(copy.text(.computers), copy.text(.laterComputer))
        case .diagnostics:
            later(copy.text(.diagnostics), copy.text(.laterDiagnostics))
        case .newTask(let starter):
            NewTaskPage(
                hostID: model.hostID,
                starter: starter,
                starterImages: model.takeSharePrefill(for: .newTask)?.images ?? [],
                workspaces: model.workspaces,
                presets: [],
                onOpenSession: { model.open(SessionSummary(sessionId: $0)) },
                loadPresets: { await model.loadAgentPresets() },
                createSession: { preset, workspaceID, cwd in
                    try await model.createNewTaskSession(preset: preset, workspaceID: workspaceID, cwd: cwd)
                },
                sendPrompt: { id, text, images in
                    try await model.sendNewTask(text, images: images, sessionID: id)
                },
                createWorkspace: { try await model.submitWorkspace($0) })
        case .addWorkspace:
            later(copy.text(.addWorkspace), copy.text(.laterAddWorkspace))
        }
    }

    private func later(_ title: String, _ message: String) -> some View {
        DLEmptyState(title: title, systemImage: "clock", message: message)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
    }
}

struct InboxArchivedPage: View {
    var model: InboxModel
    @Environment(\.locale) private var locale

    var body: some View {
        let copy = InboxCopy(locale: locale)
        Group {
            if model.archivedSessions.isEmpty {
                DLEmptyState(title: copy.text(.archivedEmpty), systemImage: "archivebox")
            } else {
                List(model.archivedSessions, id: \.sessionId) { session in
                    let title = inboxDisplayTitle(session.title)
                    DLInboxRow(
                        mailMeta: inboxWorkspaceName(session.cwd) ?? "",
                        title: title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            ? copy.text(.untitled) : title,
                        time: copy.time(inboxTime(session.updatedAt, now: model.now, calendar: model.calendar))
                    )
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle(copy.text(.archived))
        .navigationBarTitleDisplayMode(.inline)
    }
}
