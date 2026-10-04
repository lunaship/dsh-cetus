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
                .task { await model.start() }
                .onDisappear { Task { await model.stop() } }
        }
    }

    private func screen(_ copy: ConversationCopy) -> some View {
        VStack(spacing: 0) {
            if model.loadFailed {
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
                    onFrame: { model.drainFrame() })
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(DLColor.background)
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
                Button(copy.text(.menuLater)) {}
                    .disabled(true)
            } label: {
                Image(systemName: "ellipsis")
            }
            .accessibilityLabel(copy.text(.more))
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
        stoppedReason: session?.stoppedReason)
}
