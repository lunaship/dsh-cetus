import DLCore
import SwiftUI

/// 8.3 share-in sheet. Choosing a row only prefills a composer. It never sends.
struct SharePickerSheet: View {
    var record: ShareInboxRecord
    var recents: [ShareRecentSession]
    var copy: ShareCopy
    var onPick: (ShareTarget) -> Void
    var onCancel: () -> Void

    var body: some View {
        NavigationStack {
            List {
                if !record.text.isEmpty || record.kind == .image {
                    preview
                        .listRowBackground(Color.clear)
                }
                Section(copy.text(.sendTo)) {
                    choiceButton(.newTask, title: copy.text(.newTask), detail: copy.text(.newTaskDetail))
                    ForEach(recents) { session in
                        choiceButton(.session(session.id), title: session.title, detail: session.detail)
                    }
                }
            }
            .navigationTitle(copy.text(.title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(copy.text(.cancel), action: onCancel)
                        .frame(minHeight: 44)
                }
            }
        }
    }

    private var preview: some View {
        HStack(alignment: .top, spacing: 12) {
            if record.kind == .image {
                RoundedRectangle(cornerRadius: 8)
                    .fill(DLColor.secondaryFill)
                    .frame(width: 52, height: 52)
                    .accessibilityLabel(copy.text(.imageNote))
            }
            Text(record.text.isEmpty ? copy.text(.imageNote) : record.text)
                .font(DLFont.subheadline)
                .foregroundStyle(DLColor.secondaryLabel)
                .lineLimit(3)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        }
    }

    private func choiceButton(_ target: ShareTarget, title: String, detail: String) -> some View {
        Button {
            onPick(target)
        } label: {
            Label {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(DLFont.body)
                    Text(detail).font(DLFont.footnote).foregroundStyle(DLColor.secondaryLabel)
                }
            } icon: {
                Image(systemName: symbol(target))
            }
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        }
    }

    private func symbol(_ target: ShareTarget) -> String {
        switch target {
        case .newTask: "plus"
        case .session: "bubble.left.and.bubble.right"
        }
    }
}

struct ShareCopy {
    var locale: Locale

    func text(_ key: ShareText) -> String {
        L10n.string("share.\(key.rawValue)", fallback: key.fallback, locale: locale)
    }
}

enum ShareText: String {
    case title
    case sendTo
    case newTask
    case newTaskDetail
    case cancel
    case imageNote

    var fallback: String {
        switch self {
        case .title: "Send to DeepLinks"
        case .sendTo: "Send to"
        case .newTask: "New task"
        case .newTaskDetail: "Start in the current workspace"
        case .cancel: "Cancel"
        case .imageNote: "Shared image"
        }
    }
}

enum ShareSessionSource {
    static func recent(from sessions: [SessionSummary]) -> [ShareRecentSession] {
        ShareInbox.recentSessions(
            sessions,
            id: { $0.sessionId },
            title: { session in
                let title = session.title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                return title.isEmpty ? nil : title
            },
            detail: { session in
                let workspace = session.cwd?.split(separator: "/").last.map(String.init) ?? ""
                return session.running == true ? workspace : workspace
            },
            updatedAt: { $0.updatedAt }
        )
    }
}
