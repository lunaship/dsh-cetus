import UIKit

public struct ComposerSuggestion: Equatable {
    public var id: String
    public var group: String
    public var title: String
    public var detail: String

    public init(id: String, group: String, title: String, detail: String) {
        self.id = id
        self.group = group
        self.title = title
        self.detail = detail
    }
}

/// 硬件键盘合同需要知道 Shift 是否按下；文本输入路径不暴露修饰键，
/// 所以在 responder 链上记一下。
final class DLComposerTextView: UITextView {
    var shiftIsDown = false

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if presses.contains(where: { $0.key?.modifierFlags.contains(.shift) == true }) {
            shiftIsDown = true
        }
        super.pressesBegan(presses, with: event)
    }

    override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if presses.contains(where: { $0.key?.modifierFlags.contains(.shift) == true }) {
            shiftIsDown = false
        }
        super.pressesEnded(presses, with: event)
    }

    override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if presses.contains(where: { $0.key?.modifierFlags.contains(.shift) == true }) {
            shiftIsDown = false
        }
        super.pressesCancelled(presses, with: event)
    }
}

public final class DLComposerView: UIView, UITextViewDelegate {
    public var onSubmit: (() -> Void)?
    public var onEscape: (() -> Void)?
    public var onDraft: ((String) -> Void)?
    public var onDecisionSecondary: (() -> Void)?
    public var onDecisionPrimary: (() -> Void)?
    public var onSuggestion: ((String) -> Void)?
    public var onAttach: (() -> Void)?

    public var suggestions: [ComposerSuggestion] = [] {
        didSet {
            guard didFinishInit, suggestions != oldValue else { return }
            rebuildSuggestions()
            refreshMetrics()
        }
    }

    public var showsAttachButton = false {
        didSet {
            guard didFinishInit, showsAttachButton != oldValue else { return }
            attachButton.isHidden = !showsAttachButton
            refreshMetrics()
        }
    }

    public var attachTitle = "" {
        didSet { attachButton.accessibilityLabel = attachTitle }
    }

    /// SwiftUI 自己排位置时关掉。组件截图仍贴键盘。
    public var pinsToKeyboard = true
    /// 页面截图关掉实时玻璃。
    public var usesSolidSnapshotBackground = false {
        didSet { glass.useSolidSnapshotBackground(usesSolidSnapshotBackground) }
    }

    /// 外部草稿下推。相同文本不回写；组词期间不覆盖。
    public var text: String {
        get { mirrorText }
        set { applyExternalText(newValue) }
    }

    public var isEnabled = true {
        didSet {
            guard didFinishInit, isEnabled != oldValue else { return }
            applyMode(animated: false)
        }
    }

    /// 只读访问内部编辑器实例。编辑器全生命周期只有一个实例。
    public var editor: UITextView { field }

    // MARK: - 常驻视图（只创建一次）

    private let glass = DLGlassBar()
    private let field = DLComposerTextView()
    private let editorHeight: NSLayoutConstraint
    private let sendButton: UIButton
    private let attachButton = UIButton(type: .system)
    private let suggestionStack: UIStackView = {
        let stack = UIStackView()
        stack.axis = .vertical
        stack.alignment = .fill
        stack.spacing = 4
        return stack
    }()

    private let composerRoot: UIStackView = {
        let stack = UIStackView()
        stack.axis = .vertical
        stack.alignment = .fill
        stack.spacing = 4
        return stack
    }()

    private var keyboardPins: [NSLayoutConstraint] = []
    private var mirrorText = ""
    private var isApplyingExternalText = false
    private var didFinishInit = false
    private var mode: Mode = .composer
    private var installed: Installed?
    private weak var installedView: UIView?
    private var sendTitle = "Send"
    private var preferredBarHeight: CGFloat = 72
    private var lastMetricsWidth: CGFloat = -1
    private var shouldRestoreFocus = false

    private static let editorMinimumHeight: CGFloat = 44
    private static let editorMaximumHeight: CGFloat = 132

    private enum Mode {
        case composer
        case decision(
            status: String, question: String, command: String?, secondaryTitle: String, primaryTitle: String)
    }

    private enum Installed: Equatable {
        case composer
        case decision(signature: String)
    }

    public init(text: String = "", isEnabled: Bool = true, sendTitle: String = "Send") {
        self.sendTitle = sendTitle
        self.mirrorText = text
        editorHeight = field.heightAnchor.constraint(equalToConstant: Self.editorMinimumHeight)
        sendButton = dlBarButton(title: sendTitle, prominent: true, enabled: isEnabled) {}
        super.init(frame: .zero)

        glass.translatesAutoresizingMaskIntoConstraints = false
        addSubview(glass)
        NSLayoutConstraint.activate([
            glass.leadingAnchor.constraint(equalTo: leadingAnchor),
            glass.trailingAnchor.constraint(equalTo: trailingAnchor),
            glass.topAnchor.constraint(equalTo: topAnchor),
            glass.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])

        configureEditor()
        configureChrome()
        didFinishInit = true
        showComposer(animated: false)
    }

    required init?(coder: NSCoder) {
        return nil
    }

    public override func didMoveToSuperview() {
        super.didMoveToSuperview()
        NSLayoutConstraint.deactivate(keyboardPins)
        keyboardPins = []
        guard pinsToKeyboard, let superview else { return }
        translatesAutoresizingMaskIntoConstraints = false
        keyboardPins = [
            leadingAnchor.constraint(equalTo: superview.leadingAnchor),
            trailingAnchor.constraint(equalTo: superview.trailingAnchor),
            bottomAnchor.constraint(equalTo: superview.keyboardLayoutGuide.topAnchor),
            heightAnchor.constraint(greaterThanOrEqualToConstant: 72),
        ]
        NSLayoutConstraint.activate(keyboardPins)
    }

    public override func layoutSubviews() {
        super.layoutSubviews()
        guard abs(bounds.width - lastMetricsWidth) > 0.5 else { return }
        updateEditorHeight()
        refreshMetrics()
    }

    public func showComposer(animated: Bool) {
        mode = .composer
        applyMode(animated: animated)
    }

    public func showDecision(
        status: String,
        question: String,
        secondaryTitle: String,
        primaryTitle: String,
        command: String? = nil,
        animated: Bool
    ) {
        mode = .decision(
            status: status,
            question: question,
            command: command,
            secondaryTitle: secondaryTitle,
            primaryTitle: primaryTitle
        )
        applyMode(animated: animated)
    }

    public override var keyCommands: [UIKeyCommand]? {
        let send = UIKeyCommand(input: "\r", modifierFlags: .command, action: #selector(submitFromShortcut))
        send.wantsPriorityOverSystemBehavior = true
        let escape = UIKeyCommand(
            input: UIKeyCommand.inputEscape, modifierFlags: [], action: #selector(escapeFromShortcut))
        return [send, escape]
    }

    @objc public func submitFromShortcut() {
        onSubmit?()
    }

    @objc public func escapeFromShortcut() {
        onEscape?()
    }

    public func textViewDidChange(_ textView: UITextView) {
        let current = textView.text ?? ""
        guard current != mirrorText else { return }
        mirrorText = current
        updateEditorHeight()
        refreshMetrics()
        // 程序下推的文本不回声，避免 SwiftUI ↔ UIKit 回调死循环。
        guard !isApplyingExternalText else { return }
        onDraft?(current)
    }

    /// 硬件键盘合同：Return 发送，Shift-Return 换行，组词期间交回系统确认候选。
    public func textView(
        _ textView: UITextView,
        shouldChangeTextIn range: NSRange,
        replacementText text: String
    ) -> Bool {
        guard text == "\n" else { return true }
        if textView.markedTextRange != nil { return true }
        if field.shiftIsDown { return true }
        onSubmit?()
        return false
    }

    // MARK: - 内部

    private func configureEditor() {
        field.text = mirrorText
        field.font = UIFont.preferredFont(forTextStyle: .body)
        field.adjustsFontForContentSizeCategory = true
        field.backgroundColor = .clear
        field.textColor = isEnabled ? DLUIKitColor.label : DLUIKitColor.tertiaryLabel
        field.isEditable = isEnabled
        field.isSelectable = isEnabled
        field.delegate = self
        field.textContainerInset = UIEdgeInsets(top: 8, left: 0, bottom: 8, right: 0)
        field.isScrollEnabled = false
        field.setContentHuggingPriority(.defaultLow, for: .vertical)
        editorHeight.isActive = true
    }

    private func configureChrome() {
        sendButton.removeTarget(nil, action: nil, for: .touchUpInside)
        sendButton.addAction(UIAction { [weak self] _ in self?.onSubmit?() }, for: .touchUpInside)
        sendButton.setContentHuggingPriority(.required, for: .horizontal)

        attachButton.setImage(UIImage(systemName: "plus"), for: .normal)
        attachButton.accessibilityLabel = attachTitle
        attachButton.addAction(UIAction { [weak self] _ in self?.onAttach?() }, for: .touchUpInside)
        attachButton.widthAnchor.constraint(equalToConstant: 44).isActive = true
        attachButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        attachButton.isHidden = !showsAttachButton

        let row = UIStackView(arrangedSubviews: [attachButton, field, sendButton])
        row.axis = .horizontal
        row.alignment = .bottom
        row.spacing = 8

        composerRoot.addArrangedSubview(suggestionStack)
        composerRoot.addArrangedSubview(row)
    }

    private func applyExternalText(_ newValue: String) {
        guard newValue != mirrorText else { return }
        // 组词（中文/日文/语音）期间绝不用外部状态覆盖，否则打断了候选与光标。
        guard field.markedTextRange == nil else { return }
        mirrorText = newValue
        isApplyingExternalText = true
        field.text = newValue
        isApplyingExternalText = false
        clampSelection()
        updateEditorHeight()
        refreshMetrics()
    }

    private func clampSelection() {
        let length = (field.text ?? "").utf16.count
        var range = field.selectedRange
        if range.location > length { range.location = length }
        if range.location + range.length > length { range.length = max(0, length - range.location) }
        if range != field.selectedRange { field.selectedRange = range }
    }

    private func applyMode(animated: Bool) {
        switch mode {
        case .composer:
            applyComposerProperties()
            guard installed != .composer else {
                refreshMetrics()
                return
            }
            glass.install(composerRoot, animated: animated)
            installed = .composer
            installedView = composerRoot
            if shouldRestoreFocus, !field.isFirstResponder {
                field.becomeFirstResponder()
            }
            shouldRestoreFocus = false
        case .decision(let status, let question, let command, let secondaryTitle, let primaryTitle):
            let signature = [
                status, question, command ?? "", secondaryTitle, primaryTitle, isEnabled ? "1" : "0",
            ].joined(separator: "␟")
            if installed == .decision(signature: signature) { return }
            shouldRestoreFocus = installed == .composer && field.isFirstResponder
            let content = makeDecisionContent(
                status: status,
                question: question,
                command: command,
                secondaryTitle: secondaryTitle,
                primaryTitle: primaryTitle,
                enabled: isEnabled,
                onSecondary: { [weak self] in self?.onDecisionSecondary?() },
                onPrimary: { [weak self] in self?.onDecisionPrimary?() }
            )
            glass.install(content, animated: animated)
            installed = .decision(signature: signature)
            installedView = content
        }
        refreshMetrics()
    }

    private func applyComposerProperties() {
        field.isEditable = isEnabled
        field.isSelectable = isEnabled
        field.textColor = isEnabled ? DLUIKitColor.label : DLUIKitColor.tertiaryLabel
        sendButton.isEnabled = isEnabled
        attachButton.isHidden = !showsAttachButton
    }

    private func rebuildSuggestions() {
        for view in suggestionStack.arrangedSubviews {
            suggestionStack.removeArrangedSubview(view)
            view.removeFromSuperview()
        }
        suggestionStack.isHidden = suggestions.isEmpty
        var lastGroup: String?
        for suggestion in suggestions {
            if suggestion.group != lastGroup {
                let header = UILabel()
                header.text = suggestion.group
                header.font = .preferredFont(forTextStyle: .footnote)
                header.textColor = DLUIKitColor.secondaryLabel
                header.adjustsFontForContentSizeCategory = true
                suggestionStack.addArrangedSubview(header)
                lastGroup = suggestion.group
            }
            var config = UIButton.Configuration.plain()
            config.title = suggestion.title
            config.subtitle = suggestion.detail
            config.titleAlignment = .leading
            config.contentInsets = NSDirectionalEdgeInsets(top: 6, leading: 0, bottom: 6, trailing: 0)
            config.baseForegroundColor = DLUIKitColor.label
            let button = UIButton(configuration: config)
            button.contentHorizontalAlignment = .leading
            button.accessibilityIdentifier = suggestion.id
            button.addAction(
                UIAction { [weak self] _ in self?.onSuggestion?(suggestion.id) }, for: .touchUpInside)
            button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
            suggestionStack.addArrangedSubview(button)
        }
    }

    private func updateEditorHeight() {
        guard field.bounds.width > 0 else { return }
        field.isScrollEnabled = false
        let fitting = field.sizeThatFits(
            CGSize(width: field.bounds.width, height: UIView.layoutFittingExpandedSize.height))
        let target = min(max(Self.editorMinimumHeight, ceil(fitting.height)), Self.editorMaximumHeight)
        guard abs(editorHeight.constant - target) > 0.5 else { return }
        editorHeight.constant = target
        // 到上限后改为内部滚动，不再继续撑高，避免反复触发外部重排。
        field.isScrollEnabled = fitting.height > Self.editorMaximumHeight
    }

    private func refreshMetrics() {
        guard let content = installedView else { return }
        let width = bounds.width > 1 ? bounds.width : 378
        let fitted = content.systemLayoutSizeFitting(
            CGSize(width: max(1, width - 24), height: UIView.layoutFittingCompressedSize.height),
            withHorizontalFittingPriority: .required,
            verticalFittingPriority: .fittingSizeLevel)
        lastMetricsWidth = bounds.width
        let height = max(72, fitted.height + 24)
        guard abs(preferredBarHeight - height) > 0.5 else { return }
        preferredBarHeight = height
        invalidateIntrinsicContentSize()
    }

    public override var intrinsicContentSize: CGSize {
        guard !pinsToKeyboard else {
            return CGSize(width: UIView.noIntrinsicMetric, height: UIView.noIntrinsicMetric)
        }
        return CGSize(width: UIView.noIntrinsicMetric, height: preferredBarHeight)
    }
}
