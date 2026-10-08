import DLCore
import DLUI
import SwiftUI

struct ConversationBar: UIViewRepresentable {
    var decision: PhoneDecision?
    var draft: String
    var copy: ConversationCopy
    var onDraft: (String) -> Void
    var onSend: () -> Void
    var onEscape: () -> Void = {}
    var onSecondary: () -> Void
    var onPrimary: () -> Void
    var solidSnapshot = false
    var suggestions: [ComposerSuggestion] = []
    var onSuggestion: (String) -> Void = { _ in }
    var showsAttach = false
    var attachTitle = ""
    var onAttach: () -> Void = {}
    /// C03：提交在途时发送按钮进忙碌态，阻止重复点击。
    var isSending = false
    /// C05：编辑器为空时的提示文案。
    var placeholder = ""

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

    /// SwiftUI 连续更新时少做一次模式切换：只有进入/离开 decision 才需要。
    /// 同模式（composer→composer）下重新调用只是无害 no-op，但避开可以
    /// 让键盘焦点路径少走一次 install。
    private static var lastMode: [ObjectIdentifier: Bool] = [:]

    func updateUIView(_ view: DLComposerView, context: Context) {
        view.pinsToKeyboard = false
        view.usesSolidSnapshotBackground = solidSnapshot
        view.onDraft = onDraft
        view.onSubmit = onSend
        view.onEscape = onEscape
        view.onDecisionSecondary = onSecondary
        view.onDecisionPrimary = onPrimary
        view.suggestions = suggestions
        view.onSuggestion = onSuggestion
        view.showsAttachButton = showsAttach
        view.attachTitle = attachTitle
        view.onAttach = onAttach
        view.isSending = isSending
        view.placeholder = placeholder
        let inDecision = decision != nil
        let key = ObjectIdentifier(view)
        let wasInDecision = Self.lastMode[key] ?? false
        if let decision {
            if !wasInDecision {
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
                Self.lastMode[key] = true
            }
        } else {
            if wasInDecision {
                view.showComposer(animated: false)
                Self.lastMode[key] = false
            } else {
                view.text = draft
            }
        }
    }
}
