import DLCore
import DLModels
import DLUI
import SwiftUI

enum NewTaskSheet: String {
    case workspaces
    case add
    case presets
}

struct NewTaskPage: View {
    var hostID: String
    var starter: String
    var starterImages: [PromptImage] = []
    var workspaces: [WorkspaceInfo]
    var presets: [AgentPreset]
    var staticSnapshot = false
    var snapshotSheet: NewTaskSheet? = nil
    var snapshotQuery = ""
    var snapshotPending = false
    var onOpenSession: (String) -> Void = { _ in }
    var loadPresets: () async -> [AgentPreset] = { [] }
    var createSession: (String?, String?, String?) async throws -> String = { _, _, _ in throw InboxServiceError.offline
    }
    var sendPrompt: (String, String, [PromptImage]) async throws -> Void = { _, _, _ in
        throw InboxServiceError.offline
    }
    var createWorkspace: (String) async throws -> WorkspaceWriteResult = { _ in throw InboxServiceError.offline }

    @Environment(\.locale) private var locale
    @State private var draft = ""
    @State private var images: [PromptImage] = []
    @State private var saved = ""
    @State private var workspace: WorkspaceInfo?
    @State private var preset: AgentPreset?
    @State private var livePresets: [AgentPreset] = []
    @State private var sheet: NewTaskSheet?
    @State private var query = ""
    @State private var addPath = ""
    @State private var pendingPath: String?
    @State private var knownWorkspaces: [WorkspaceInfo] = []
    /// 提交工作区失败时的可操作说明（C15 §19.2 R3：动作要有结果）。
    @State private var submitError: String?

    var body: some View {
        let copy = NewTaskCopy(locale: locale)
        let shownPresets = livePresets.isEmpty ? presets : livePresets
        Group {
            if let snapshotSheet {
                sheetBody(snapshotSheet, copy: copy, presets: shownPresets)
            } else {
                draftBody(copy, presets: shownPresets)
            }
        }
        .navigationTitle(copy.text(.title))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: staticSnapshot ? .constant(nil) : $sheet) { item in
            NavigationStack { sheetBody(item, copy: copy, presets: shownPresets) }
        }
        .onAppear {
            knownWorkspaces = workspaces
            if workspace == nil { workspace = workspaces.first }
            if preset == nil { preset = shownPresets.first { $0.isDefault == true } ?? shownPresets.first }
            saved = NewTaskDraftStore.load(hostID: hostID)
            if !starter.isEmpty {
                // C13 要求 2：分享内容**并入**已有草稿，不能覆盖用户已经写下的字。
                // 已有草稿优先取当前编辑中的 draft，其次取本地持久化的 saved。
                let base = draft.isEmpty ? saved : draft
                let merged = ShareInbox.merging(draft: base, shared: starter)
                draft = merged.text
                images = ShareInbox.mergingImages(existing: images, shared: starterImages)
            } else if draft.isEmpty {
                draft = saved
            }
            query = snapshotQuery
            if snapshotPending { pendingPath = "/tmp/notes" }
        }
        .task {
            guard !staticSnapshot else { return }
            let loaded = await loadPresets()
            if !loaded.isEmpty { livePresets = loaded }
        }
    }

    private func draftBody(_ copy: NewTaskCopy, presets: [AgentPreset]) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            if !saved.isEmpty, draft != saved {
                Button(copy.text(.continueLast)) { draft = saved }
                    .buttonStyle(.bordered)
                    .frame(minHeight: 44)
            }
            HStack {
                Text(workspaceTitle)
                    .font(DLFont.headline)
                    .lineLimit(1)
                Spacer()
                Button(copy.text(.more)) { sheet = .workspaces }
                    .frame(minHeight: 44)
            }
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    ForEach(presets.prefix(4)) { item in
                        Button(item.name ?? item.id) { preset = item }
                            .buttonStyle(.bordered)
                            .buttonBorderShape(.capsule)
                            .tint(preset?.id == item.id ? DLColor.accent : DLColor.secondaryLabel)
                            .frame(minHeight: 44)
                    }
                    Button(copy.text(.presets)) { sheet = .presets }
                        .frame(minHeight: 44)
                }
            }
            Spacer(minLength: 0)
            NewTaskComposer(
                text: draft, sendTitle: copy.text(.send), solid: staticSnapshot,
                onDraft: { text in
                    draft = text
                    NewTaskDraftStore.save(hostID: hostID, text: text)
                },
                onSend: { Task { await send(copy) } })
        }
        .padding(16)
    }

    @ViewBuilder private func sheetBody(_ item: NewTaskSheet, copy: NewTaskCopy, presets: [AgentPreset]) -> some View {
        switch item {
        case .workspaces:
            List {
                ForEach(Array(workspacePickRows(workspaces: knownWorkspaces, query: query).enumerated()), id: \.offset)
                {
                    _, row in
                    switch row {
                    case .listed(let room):
                        Button(room.title ?? room.path ?? "") { workspace = room }
                    case .usePath(let path):
                        Button(copy.format(.usePath, path)) {
                            addPath = path
                            sheet = .add
                        }
                    }
                }
                Button(copy.text(.addWorkspace)) { sheet = .add }
            }
            // 截图模式用常量绑定：初始文字在搜索栏创建时就位。onAppear 再写入会让清除按钮
            // 时有时无，导致 3_2_workspace 基线在重生成与对比之间来回不一致。
            .searchable(text: staticSnapshot ? .constant(snapshotQuery) : $query)
            .navigationTitle(copy.text(.chooseWorkspace))
        case .add:
            Form {
                TextField(copy.text(.path), text: $addPath)
                if let pendingPath {
                    Text(copy.format(.pending, pendingPath))
                        .foregroundStyle(DLColor.secondaryLabel)
                }
                if let submitError {
                    Text(submitError)
                        .font(DLFont.footnote)
                        .foregroundStyle(DLColor.err)
                }
                Section(copy.text(.recent)) {
                    ForEach(knownWorkspaces, id: \.path) { room in
                        Button(room.path ?? "") { addPath = room.path ?? "" }
                    }
                }
                Button(copy.text(.submit)) { Task { await submitWorkspace(copy) } }
                    .frame(minHeight: 44)
            }
            .navigationTitle(copy.text(.addWorkspace))
        case .presets:
            List(presets) { item in
                Button {
                    preset = item
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(item.name ?? item.id).font(DLFont.headline)
                        if let description = item.description, !description.isEmpty {
                            Text(description).font(DLFont.footnote).foregroundStyle(DLColor.secondaryLabel)
                        }
                    }
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                }
            }
            .navigationTitle(copy.text(.presets))
        }
    }

    private var workspaceTitle: String {
        workspace?.title ?? workspace?.path ?? ""
    }

    private func send(_ copy: NewTaskCopy) async {
        _ = copy
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty || !images.isEmpty, !staticSnapshot else { return }
        do {
            let id = try await createSession(preset?.id, workspace?.workspaceId, workspace?.path)
            try await sendPrompt(id, text, images)
            draft = ""
            images = []
            NewTaskDraftStore.save(hostID: hostID, text: "")
            onOpenSession(id)
        } catch {}
    }

    /// 提交新工作区路径。
    ///
    /// C15 §19.2 R3：动作必须给出**结果**，不能只把按钮画出来。
    /// 旧实现是 `catch {}` —— 路径非法、没权限、电脑离线全都静默失败，
    /// 用户点了「提交」什么也没发生，也不知道为什么。现在失败会落到 `pendingPath`
    /// 之外的显式错误行上（`submitError`），与「已提交等待确认」区分开。
    private func submitWorkspace(_ copy: NewTaskCopy) async {
        let path = addPath.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !path.isEmpty, !staticSnapshot else { return }
        do {
            switch try await createWorkspace(path) {
            case .created(let room):
                knownWorkspaces.append(room)
                workspace = room
                pendingPath = nil
                submitError = nil
            case .pending(let submitted):
                pendingPath = submitted
                submitError = nil
            }
        } catch {
            // 保留用户输入（不清空 addPath），只显示可操作说明。
            submitError = copy.text(.addWorkspaceFailed)
        }
    }
}

extension NewTaskSheet: Identifiable {
    var id: String { rawValue }
}

private struct NewTaskComposer: UIViewRepresentable {
    var text: String
    var sendTitle: String
    var solid: Bool
    var onDraft: (String) -> Void
    var onSend: () -> Void

    func makeUIView(context: Context) -> DLComposerView {
        let view = DLComposerView(text: text, sendTitle: sendTitle)
        view.pinsToKeyboard = false
        view.usesSolidSnapshotBackground = solid
        return view
    }

    /// 输入区高度只由内容决定：不交给固有尺寸 + 优先级去协商，否则 SwiftUI 会把多出的空间
    /// 分给它，内部谁被拉高不确定，截图在两次运行之间不一致。
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: DLComposerView, context: Context) -> CGSize? {
        guard let width = proposal.width, width.isFinite, width > 1 else { return nil }
        return CGSize(width: width, height: uiView.fittingHeight(width: width))
    }

    func updateUIView(_ view: DLComposerView, context: Context) {
        view.pinsToKeyboard = false
        view.usesSolidSnapshotBackground = solid
        view.onDraft = onDraft
        view.onSubmit = onSend
        if view.text != text { view.text = text }
        view.showComposer(animated: false)
    }
}

enum NewTaskDraftStore {
    /// 新任务还没有会话，所以键用 `draftID`（固定的"新任务"槽位）而不是 sessionID。
    /// 这样它不会跟任何已存在会话的草稿混在一起（C02 要求 1）。
    static func load(hostID: String) -> String {
        ComposerDraftStore(directory: directory).load(
            ComposerDraftKey(hostID: hostID, draftID: newTaskDraftID))?.text ?? ""
    }

    static func save(hostID: String, text: String) {
        ComposerDraftStore(directory: directory).save(
            ComposerDraftKey(hostID: hostID, draftID: newTaskDraftID), text: text)
    }

    static let newTaskDraftID = "new-task"

    private static var directory: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("deeplinks-new-task", isDirectory: true)
    }
}

struct NewTaskCopy {
    var locale: Locale

    func text(_ key: NewTaskText) -> String {
        L10n.string("newTask.\(key.rawValue)", fallback: key.fallback, locale: locale)
    }

    func format(_ key: NewTaskText, _ arguments: CVarArg...) -> String {
        String(format: text(key), locale: locale, arguments: arguments)
    }
}

enum NewTaskText: String {
    case title
    case continueLast
    case more
    case presets
    case send
    case chooseWorkspace
    case usePath
    case addWorkspace
    case path
    case pending
    case recent
    case submit
    /// C15 §19.2 R3：提交失败要有可操作说明，不能静默。
    case addWorkspaceFailed

    var fallback: String {
        switch self {
        case .title: "New task"
        case .continueLast: "Continue the last task"
        case .more: "More"
        case .presets: "Agents"
        case .send: "Send"
        case .chooseWorkspace: "Workspace"
        case .usePath: "Use %@"
        case .addWorkspace: "Add workspace"
        case .path: "Path"
        case .pending: "Submitted %@. Waiting for the computer."
        case .recent: "Recent"
        case .submit: "Submit"
        case .addWorkspaceFailed: "Couldn't add this folder. Check the path and the connection."
        }
    }
}
