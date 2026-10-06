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
                draft = starter
                images = starterImages
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
            .searchable(text: $query)
            .navigationTitle(copy.text(.chooseWorkspace))
        case .add:
            Form {
                TextField(copy.text(.path), text: $addPath)
                if let pendingPath {
                    Text(copy.format(.pending, pendingPath))
                        .foregroundStyle(DLColor.secondaryLabel)
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

    private func submitWorkspace(_ copy: NewTaskCopy) async {
        _ = copy
        let path = addPath.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !path.isEmpty, !staticSnapshot else { return }
        do {
            switch try await createWorkspace(path) {
            case .created(let room):
                knownWorkspaces.append(room)
                workspace = room
                pendingPath = nil
            case .pending(let submitted):
                pendingPath = submitted
            }
        } catch {}
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
    static func load(hostID: String) -> String {
        ComposerDraftStore(directory: directory).load(hostID: hostID)
    }

    static func save(hostID: String, text: String) {
        ComposerDraftStore(directory: directory).save(hostID: hostID, text: text)
    }

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
        }
    }
}
