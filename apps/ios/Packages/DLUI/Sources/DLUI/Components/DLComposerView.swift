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

/// 设计稿 4.1：输入区底部的模型 / 权限胶囊。点按交给宿主打开对应面板。
public struct ComposerChip: Equatable {
    public var id: String
    public var title: String
    public var systemImage: String
    public var accessibilityLabel: String

    public init(id: String, title: String, systemImage: String, accessibilityLabel: String) {
        self.id = id
        self.title = title
        self.systemImage = systemImage
        self.accessibilityLabel = accessibilityLabel
    }
}

/// 硬件键盘合同需要追踪 Shift 是否按下（Return 发送、Shift-Return 换行）；
/// 文本输入路径不暴露修饰键，所以在 responder 链上记一下。
///
/// public：编辑器由 `DLComposerView.editor` 以 `UITextView` 暴露，测试需要
/// cast 回本类型并读写 `shiftIsDown`，所以类与属性都要 public。
public final class DLComposerTextView: UITextView {
    public var shiftIsDown = false

    public override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if presses.contains(where: { $0.key?.modifierFlags.contains(.shift) == true }) {
            shiftIsDown = true
        }
        super.pressesBegan(presses, with: event)
    }

    public override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if presses.contains(where: { $0.key?.modifierFlags.contains(.shift) == true }) {
            shiftIsDown = false
        }
        super.pressesEnded(presses, with: event)
    }

    public override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
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
    /// 设计稿 4.4：点选项（交回选项值）。
    public var onDecisionChoice: ((String) -> Void)?
    /// 设计稿 4.4：自由回答输入框的文字变化。
    public var onDecisionAnswer: ((String) -> Void)?
    public var onSuggestion: ((String) -> Void)?
    public var onAttach: (() -> Void)?

    public var suggestions: [ComposerSuggestion] = [] {
        didSet {
            guard didFinishInit, suggestions != oldValue else { return }
            rebuildSuggestions()
            refreshMetrics()
        }
    }

    /// 设计稿 4.1：模型 / 权限胶囊，排在附件按钮和发送按钮之间。
    public var chips: [ComposerChip] = [] {
        didSet {
            guard didFinishInit, chips != oldValue else { return }
            rebuildChips()
            refreshMetrics()
        }
    }

    public var onChip: ((String) -> Void)?

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

    /// C03：提交在途时禁用发送按钮并改文案，防止重复提交。
    public var isSending = false {
        didSet {
            guard didFinishInit, isSending != oldValue else { return }
            sendButton.isEnabled = isEnabled && !isSending
            sendButton.alpha = isSending ? 0.5 : 1
        }
    }

    /// C05：编辑器为空时显示的提示。不抢焦点，不进发送正文。
    /// 空闲态用"给这个会话发消息"，运行中用"补充说明"。
    public var placeholder = "" {
        didSet {
            placeholderLabel.text = placeholder
            updatePlaceholderVisibility()
        }
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
    /// 设计稿 4.4：提问卡里的自由回答输入框。全生命周期一个实例，切题重装时保留焦点。
    private let answerField: UITextField = {
        let field = UITextField()
        field.font = .preferredFont(forTextStyle: .body)
        field.adjustsFontForContentSizeCategory = true
        field.textColor = DLUIKitColor.label
        field.borderStyle = .none
        field.clearButtonMode = .whileEditing
        field.returnKeyType = .done
        let pencil = UIImageView(image: UIImage(systemName: "pencil"))
        pencil.tintColor = DLUIKitColor.tertiaryLabel
        pencil.preferredSymbolConfiguration = UIImage.SymbolConfiguration(textStyle: .body)
        pencil.contentMode = .center
        pencil.frame = CGRect(x: 0, y: 0, width: 28, height: 24)
        field.leftView = pencil
        field.leftViewMode = .always
        field.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        return field
    }()
    private let placeholderLabel: UILabel = {
        let label = UILabel()
        label.font = .preferredFont(forTextStyle: .body)
        label.textColor = .tertiaryLabel
        label.adjustsFontForContentSizeCategory = true
        label.isHidden = true
        return label
    }()

    private let chipStack: UIStackView = {
        let stack = UIStackView()
        stack.axis = .horizontal
        stack.alignment = .center
        stack.spacing = 6
        return stack
    }()

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
            status: String, question: String, command: String?, secondaryTitle: String, primaryTitle: String,
            positionText: String?, notice: String?, answer: DecisionAnswerInput?)
    }

    private enum Installed: Equatable {
        case composer
        case decision(signature: String)
    }

    public init(text: String = "", isEnabled: Bool = true, sendTitle: String = "Send") {
        self.sendTitle = sendTitle
        self.mirrorText = text
        editorHeight = field.heightAnchor.constraint(equalToConstant: Self.editorMinimumHeight)
        sendButton = dlRoundSendButton(title: sendTitle, enabled: isEnabled)
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
        configurePlaceholder()
        answerField.addAction(
            UIAction { [weak self] _ in
                guard let self else { return }
                self.onDecisionAnswer?(self.answerField.text ?? "")
            }, for: .editingChanged)
        answerField.addAction(
            UIAction { [weak self] _ in self?.answerField.resignFirstResponder() }, for: .editingDidEndOnExit)
        didFinishInit = true
        showComposer(animated: false)
    }

    private func configurePlaceholder() {
        field.translatesAutoresizingMaskIntoConstraints = false
        placeholderLabel.translatesAutoresizingMaskIntoConstraints = false
        composerRoot.addArrangedSubview(placeholderLabel)
        // placeholder 叠在 field 上（同位置，不占额外空间）
        field.addSubview(placeholderLabel)
        NSLayoutConstraint.activate([
            // 与 textContainerInset（上 8）和 lineFragmentPadding（5）对齐，提示与光标同一位置。
            // 用 frameLayoutGuide：UITextView 是滚动视图，直接贴 field 的锚点会落到内容区，
            // 提示宽度不确定，截图在重生成与对比之间来回不一致。
            placeholderLabel.topAnchor.constraint(equalTo: field.frameLayoutGuide.topAnchor, constant: 8),
            placeholderLabel.leadingAnchor.constraint(equalTo: field.frameLayoutGuide.leadingAnchor, constant: 5),
            placeholderLabel.trailingAnchor.constraint(equalTo: field.frameLayoutGuide.trailingAnchor, constant: -5),
        ])
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
        positionText: String? = nil,
        notice: String? = nil,
        answer: DecisionAnswerInput? = nil,
        animated: Bool
    ) {
        mode = .decision(
            status: status,
            question: question,
            command: command,
            secondaryTitle: secondaryTitle,
            primaryTitle: primaryTitle,
            positionText: positionText,
            notice: notice,
            answer: answer
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
        updatePlaceholderVisibility()
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

        // 设计稿 4.1：编辑器独占一行；下面一行是 附件 · 模型 / 权限胶囊 · 圆形发送。
        chipStack.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        chipStack.setContentHuggingPriority(.defaultLow, for: .horizontal)
        chipStack.isHidden = chips.isEmpty
        let spacer = UIView()
        spacer.setContentHuggingPriority(.init(1), for: .horizontal)
        spacer.setContentCompressionResistancePriority(.init(1), for: .horizontal)
        let tools = UIStackView(arrangedSubviews: [attachButton, chipStack, spacer, sendButton])
        tools.axis = .horizontal
        tools.alignment = .center
        tools.spacing = 8

        composerRoot.addArrangedSubview(suggestionStack)
        composerRoot.addArrangedSubview(field)
        composerRoot.addArrangedSubview(tools)
    }

    private func rebuildChips() {
        for view in chipStack.arrangedSubviews {
            chipStack.removeArrangedSubview(view)
            view.removeFromSuperview()
        }
        chipStack.isHidden = chips.isEmpty
        for chip in chips {
            var config = UIButton.Configuration.gray()
            config.title = chip.title
            config.image = UIImage(systemName: chip.systemImage)
            config.imagePadding = 4
            config.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(textStyle: .footnote)
            config.cornerStyle = .capsule
            config.baseForegroundColor = DLUIKitColor.label
            config.titleLineBreakMode = .byTruncatingTail
            config.contentInsets = NSDirectionalEdgeInsets(top: 6, leading: 10, bottom: 6, trailing: 10)
            config.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
                var outgoing = incoming
                outgoing.font = UIFont.preferredFont(forTextStyle: .footnote)
                return outgoing
            }
            let id = chip.id
            let button = UIButton(
                configuration: config, primaryAction: UIAction { [weak self] _ in self?.onChip?(id) })
            button.accessibilityLabel = chip.accessibilityLabel
            button.accessibilityIdentifier = "composer.chip.\(chip.id)"
            button.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
            button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
            chipStack.addArrangedSubview(button)
        }
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
        updatePlaceholderVisibility()
    }

    /// C05：没有提示文案、或编辑器里已有文字时隐藏提示；否则显示。
    private func updatePlaceholderVisibility() {
        placeholderLabel.isHidden = placeholder.isEmpty || !(field.text ?? "").isEmpty
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
        case .decision(
            let status, let question, let command, let secondaryTitle, let primaryTitle, let positionText, let notice,
            let answer
        ):
            // 回答文字不进签名：用户打字时不重装视图，焦点和输入法候选都不受影响。
            let signature = [
                status, question, command ?? "", secondaryTitle, primaryTitle, isEnabled ? "1" : "0",
                positionText ?? "", notice ?? "", answer?.signature ?? "",
            ].joined(separator: "␟")
            if installed == .decision(signature: signature) {
                syncAnswerText(answer)
                return
            }
            shouldRestoreFocus = installed == .composer && field.isFirstResponder
            let answerHadFocus = answerField.isFirstResponder
            syncAnswerText(answer, force: true)
            let content = makeDecisionContent(
                status: status,
                question: question,
                command: command,
                secondaryTitle: secondaryTitle,
                primaryTitle: primaryTitle,
                enabled: isEnabled,
                positionText: positionText,
                notice: notice,
                answer: answer,
                answerField: answer == nil ? nil : answerField,
                onChoice: { [weak self] value in self?.onDecisionChoice?(value) },
                onSecondary: { [weak self] in self?.onDecisionSecondary?() },
                onPrimary: { [weak self] in self?.onDecisionPrimary?() }
            )
            glass.install(content, animated: animated)
            installed = .decision(signature: signature)
            installedView = content
            if answerHadFocus, answer != nil, isEnabled, !answerField.isFirstResponder {
                answerField.becomeFirstResponder()
            }
        }
        refreshMetrics()
    }

    /// 外部回答文字下推。正在输入（有焦点或组词中）时不覆盖，切题重装时强制对齐。
    private func syncAnswerText(_ answer: DecisionAnswerInput?, force: Bool = false) {
        guard let answer, answerField.markedTextRange == nil else { return }
        guard force || !answerField.isFirstResponder else { return }
        if (answerField.text ?? "") != answer.text {
            answerField.text = answer.text
        }
    }

    private func applyComposerProperties() {
        field.isEditable = isEnabled
        field.isSelectable = isEnabled
        field.textColor = isEnabled ? DLUIKitColor.label : DLUIKitColor.tertiaryLabel
        sendButton.isEnabled = isEnabled && !isSending
        sendButton.alpha = isSending ? 0.5 : 1
        attachButton.isHidden = !showsAttachButton
        chipStack.isHidden = chips.isEmpty
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

/// 设计稿 4.1：圆形发送按钮，只放图标；读屏和 UI 测试仍按「发送 / Send」找到它。
/// 用标签色实心圆（浅色黑底、深色白底），不占用这一屏唯一的品牌实心名额。
@MainActor
func dlRoundSendButton(title: String, enabled: Bool) -> UIButton {
    var config = UIButton.Configuration.filled()
    config.image = UIImage(systemName: "arrow.up")
    config.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(textStyle: .headline)
    config.cornerStyle = .capsule
    config.baseBackgroundColor = DLUIKitColor.label
    config.baseForegroundColor = DLUIKitColor.background
    config.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 8)
    let button = UIButton(configuration: config)
    button.accessibilityLabel = title
    button.isEnabled = enabled
    button.widthAnchor.constraint(equalToConstant: 44).isActive = true
    button.heightAnchor.constraint(equalToConstant: 44).isActive = true
    return button
}
