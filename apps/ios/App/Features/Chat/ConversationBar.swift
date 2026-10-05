import DLCore
import DLUI
import SwiftUI

struct ConversationBar: UIViewRepresentable {
    var decision: PhoneDecision?
    var draft: String
    var copy: ConversationCopy
    var onDraft: (String) -> Void
    var onSend: () -> Void
    var onSecondary: () -> Void
    var onPrimary: () -> Void
    var solidSnapshot = false

    func makeUIView(context: Context) -> DLComposerView {
        let view = DLComposerView(sendTitle: copy.text(.send))
        view.pinsToKeyboard = false
        view.usesSolidSnapshotBackground = solidSnapshot
        return view
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: DLComposerView, context: Context) -> CGSize? {
        let width = proposal.width ?? uiView.bounds.width
        let height = uiView.intrinsicContentSize.height
        guard height > 1, height < 10_000 else { return nil }
        return CGSize(width: width, height: height)
    }

    func updateUIView(_ view: DLComposerView, context: Context) {
        view.pinsToKeyboard = false
        view.usesSolidSnapshotBackground = solidSnapshot
        view.onDraft = onDraft
        view.onSubmit = onSend
        view.onDecisionSecondary = onSecondary
        view.onDecisionPrimary = onPrimary
        if let decision {
            switch decision {
            case .approval(let message):
                view.showDecision(
                    status: copy.text(.waitApproval),
                    question: message.text.isEmpty ? (message.toolName ?? copy.text(.approval)) : message.text,
                    secondaryTitle: copy.text(.reject),
                    primaryTitle: copy.text(.allowOnce),
                    command: approvalCommand(from: message.toolArgs),
                    animated: false)
            case .question(let message):
                view.showDecision(
                    status: copy.text(.waitAnswer),
                    question: message.text.isEmpty ? copy.text(.question) : message.text,
                    secondaryTitle: copy.text(.reject),
                    primaryTitle: copy.text(.send),
                    command: nil,
                    animated: false)
            }
        } else {
            if view.text != draft { view.text = draft }
            view.showComposer(animated: false)
        }
    }
}
