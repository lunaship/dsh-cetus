import DLCore
import DLModels
import DLNet
import DLUI
import SwiftUI

struct InboxFlowView: View {
    @Environment(\.scenePhase) private var scenePhase
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
            _model = State(
                initialValue: InboxModel(
                    hostID: hostID, service: InboxLiveService(hostID: hostID), cache: InboxDiskCache()))
        }
    }

    var body: some View {
        InboxPage(model: model)
            .onAppear { model.onSwitch = onSwitch }
            .onChange(of: model.missingHost) { _, missing in
                if missing { onMissing() }
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
    /// Inbox snapshots only. Production still calls `start()`.
    var staticSnapshot = false

    var body: some View {
        let copy = InboxCopy(locale: locale)
        if staticSnapshot {
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
            .sensoryFeedback(.success, trigger: model.approvalTick)
        }
    }

    private func screen(_ copy: InboxCopy) -> some View {
        inbox(copy)
            .navigationTitle(copy.text(.brand))
            .navigationSubtitle(copy.subtitle(name: model.displayName, link: model.link))
            .navigationBarTitleDisplayMode(.large)
            .toolbarTitleMenu { titleMenu(copy) }
            .toolbar { toolbar(copy) }
    }

    private var renamePresented: Binding<Bool> {
        Binding(get: { model.renameTarget != nil }, set: { if !$0 { model.renameTarget = nil } })
    }

    private var deletePresented: Binding<Bool> {
        Binding(get: { model.deleteTarget != nil }, set: { if !$0 { model.deleteTarget = nil } })
    }

    private func deleteName(_ copy: InboxCopy) -> String {
        let title = inboxDisplayTitle(model.deleteTarget?.title)
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? copy.text(.untitled) : trimmed
    }

    @ViewBuilder private func inbox(_ copy: InboxCopy) -> some View {
        List {
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
            case .offlineEmpty:
                empty(
                    copy, title: copy.format(.offlineTitle, model.displayName),
                    message: copy.text(.offlineHintPlain), symbol: "wifi.slash")
            case .search(let groups, let degraded, let failed):
                searchResults(copy, groups: groups, degraded: degraded, failed: failed)
            case .sections(let groups):
                ForEach(groups, id: \.section) { group in
                    Section {
                        ForEach(group.sessions, id: \.sessionId) { session in
                            sessionRow(session, copy: copy, needle: "")
                        }
                    } header: {
                        Text("\(copy.section(group.section))  \(group.sessions.count)")
                    }
                }
            }
        }
        .listStyle(.plain)
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
        _ session: SessionSummary, copy: InboxCopy, needle: String, snippet: String? = nil
    ) -> some View {
        let offline = model.link == .offline
        let content = inboxRowContent(session: session, action: model.phoneAction, offline: offline)
        let title = displayTitle(session, copy: copy)
        let preview = snippet ?? content.preview.map { copy.preview($0) }
        return VStack(alignment: .leading, spacing: 8) {
            DLInboxRow(
                mailMeta: copy.meta(
                    workspace: content.workspace, subagents: content.subagentCount, status: content.status),
                title: title,
                time: copy.time(inboxTime(session.updatedAt, now: model.now, calendar: model.calendar)),
                preview: preview,
                command: content.command,
                dot: dot(content.dot),
                needle: needle,
                isEnabled: true
            )
            .contentShape(Rectangle())
            .onTapGesture { model.open(session) }
            actions(content, session: session, copy: copy)
        }
        .contextMenu { inboxSessionActions(session, model: model, copy: copy) }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if content.allowsSwipe {
                Button(copy.text(.delete), role: .destructive) { model.askDelete(session) }
                Button(copy.text(.archive)) { Task { await model.archive(session) } }
                    .tint(.gray)
            }
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private func actions(_ content: InboxRowContent, session: SessionSummary, copy: InboxCopy) -> some View
    {
        if content.pending == .approval {
            HStack(spacing: 8) {
                Button(copy.text(.reject)) { Task { await model.decide(allow: false) } }
                    .buttonStyle(.bordered)
                Button(copy.text(.allowOnce)) { Task { await model.decide(allow: true) } }
                    .buttonStyle(.bordered)
                    .tint(DLColor.accent)
            }
            .disabled(!model.actionsEnabled)
            .accessibilityHint(model.actionsEnabled ? "" : copy.text(.approveBlocked))
        } else if content.pending == .question {
            Button(copy.text(.answer)) { model.open(session) }
                .buttonStyle(.bordered)
                .disabled(!model.actionsEnabled)
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
        Section(copy.text(.workspaces)) {
            Button {
                model.setWorkspace(nil)
            } label: {
                if model.tokens.isEmpty {
                    Label(copy.text(.allWorkspaces), systemImage: "checkmark")
                } else {
                    Text(copy.text(.allWorkspaces))
                }
            }
            ForEach(inboxVisibleWorkspaces(model.workspaces), id: \.self) { path in
                let name = inboxWorkspaceName(path) ?? path
                Button {
                    model.setWorkspace(path)
                } label: {
                    if model.tokens.first?.path == path {
                        Label(name, systemImage: "checkmark")
                    } else {
                        Text(name)
                    }
                }
            }
            Button(copy.text(.addWorkspace)) { model.path.append(.addWorkspace) }
            Button(copy.text(.archived)) { model.path.append(.archived) }
        }
    }

    @ToolbarContentBuilder private func toolbar(_ copy: InboxCopy) -> some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            Button {
                model.path.append(.settings)
            } label: {
                Image(systemName: "gearshape")
            }
            .accessibilityLabel(copy.text(.settings))
        }
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
    Button(copy.text(.rename)) { model.askRename(session) }
    Button(copy.text(.fork)) { Task { await model.fork(session) } }
    if let id = session.sessionId {
        ShareLink(item: copy.format(.shareBody, inboxDisplayTitle(session.title), id)) {
            Label(copy.text(.share), systemImage: "square.and.arrow.up")
        }
    }
    Button(copy.text(.archive)) { Task { await model.archive(session) } }
    Button(copy.text(.delete), role: .destructive) { model.askDelete(session) }
}

struct InboxDestinationPage: View {
    var destination: InboxDestination
    var model: InboxModel
    @Environment(\.locale) private var locale

    var body: some View {
        let copy = InboxCopy(locale: locale)
        switch destination {
        case .archived:
            InboxArchivedPage(model: model)
        case .session:
            later(copy.text(.brand), copy.text(.laterSession))
        case .settings:
            later(copy.text(.settings), copy.text(.laterSettings))
        case .computer:
            later(copy.text(.computers), copy.text(.laterComputer))
        case .diagnostics:
            later(copy.text(.diagnostics), copy.text(.laterDiagnostics))
        case .newTask(let starter):
            later(copy.text(.newTask), starter.isEmpty ? copy.text(.laterNewTask) : starter)
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
