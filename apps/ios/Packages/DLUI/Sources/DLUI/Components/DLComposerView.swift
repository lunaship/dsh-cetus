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

public final class DLComposerView: UIView, UITextViewDelegate {
    public var onSubmit: (() -> Void)?
    public var onEscape: (() -> Void)?
    public var onDraft: ((String) -> Void)?
    public var onDecisionSecondary: (() -> Void)?
    public var onDecisionPrimary: (() -> Void)?
    public var onSuggestion: ((String) -> Void)?
    public var onAttach: (() -> Void)?
    public var suggestions: [ComposerSuggestion] = []
    public var showsAttachButton = false
    public var attachTitle = ""
    /// SwiftUI 自己排位置时关掉。组件截图仍贴键盘。
    public var pinsToKeyboard = true
    /// 页面截图关掉实时玻璃。
    public var usesSolidSnapshotBackground = false {
        didSet { glass.useSolidSnapshotBackground(usesSolidSnapshotBackground) }
    }

    public var text: String = "" {
        didSet {
            guard !isSyncingText, field?.text != text else { return }
            field?.text = text
        }
    }

    public var isEnabled = true {
        didSet {
            guard didFinishInit, isEnabled != oldValue else { return }
            applyMode(animated: false)
        }
    }

    private let glass = DLGlassBar()
    private var keyboardPins: [NSLayoutConstraint] = []
    private weak var field: UITextView?
    private var isSyncingText = false
    private var didFinishInit = false
    private var mode: Mode = .composer
    private var sendTitle = "Send"
    private var preferredBarHeight: CGFloat = 72

    private enum Mode {
        case composer
        case decision(
            status: String, question: String, command: String?, secondaryTitle: String, primaryTitle: String)
    }

    public init(text: String = "", isEnabled: Bool = true, sendTitle: String = "Send") {
        super.init(frame: .zero)
        self.text = text
        self.isEnabled = isEnabled
        self.sendTitle = sendTitle
        glass.translatesAutoresizingMaskIntoConstraints = false
        addSubview(glass)
        NSLayoutConstraint.activate([
            glass.leadingAnchor.constraint(equalTo: leadingAnchor),
            glass.trailingAnchor.constraint(equalTo: trailingAnchor),
            glass.topAnchor.constraint(equalTo: topAnchor),
            glass.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
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
        isSyncingText = true
        text = textView.text ?? ""
        isSyncingText = false
        onDraft?(text)
    }

    private func applyMode(animated: Bool) {
        let content: UIView
        switch mode {
        case .composer:
            content = makeComposerContent()
        case .decision(let status, let question, let command, let secondaryTitle, let primaryTitle):
            content = makeDecisionContent(
                status: status,
                question: question,
                command: command,
                secondaryTitle: secondaryTitle,
                primaryTitle: primaryTitle,
                enabled: isEnabled,
                onSecondary: { [weak self] in self?.onDecisionSecondary?() },
                onPrimary: { [weak self] in self?.onDecisionPrimary?() }
            )
        }
        let width = bounds.width > 1 ? bounds.width : 378
        let fitted = content.systemLayoutSizeFitting(
            CGSize(width: max(1, width - 24), height: UIView.layoutFittingCompressedSize.height),
            withHorizontalFittingPriority: .required,
            verticalFittingPriority: .fittingSizeLevel)
        preferredBarHeight = max(72, fitted.height + 24)
        glass.install(content, animated: animated)
        invalidateIntrinsicContentSize()
    }

    public override var intrinsicContentSize: CGSize {
        guard !pinsToKeyboard else {
            return CGSize(width: UIView.noIntrinsicMetric, height: UIView.noIntrinsicMetric)
        }
        return CGSize(width: UIView.noIntrinsicMetric, height: preferredBarHeight)
    }

    private func makeComposerContent() -> UIView {
        let field = UITextView()
        field.text = text
        field.font = UIFont.preferredFont(forTextStyle: .body)
        field.adjustsFontForContentSizeCategory = true
        field.backgroundColor = .clear
        field.textColor = isEnabled ? DLUIKitColor.label : DLUIKitColor.tertiaryLabel
        field.isEditable = isEnabled
        field.isSelectable = isEnabled
        field.delegate = self
        field.textContainerInset = UIEdgeInsets(top: 8, left: 0, bottom: 8, right: 0)
        field.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        self.field = field

        let send = dlBarButton(title: sendTitle, prominent: true, enabled: isEnabled) { [weak self] in
            self?.onSubmit?()
        }
        send.setContentHuggingPriority(.required, for: .horizontal)
        var rowItems: [UIView] = []
        if showsAttachButton {
            let plus = UIButton(type: .system)
            plus.setImage(UIImage(systemName: "plus"), for: .normal)
            plus.accessibilityLabel = attachTitle
            plus.addAction(UIAction { [weak self] _ in self?.onAttach?() }, for: .touchUpInside)
            plus.widthAnchor.constraint(equalToConstant: 44).isActive = true
            plus.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
            rowItems.append(plus)
        }
        rowItems.append(field)
        rowItems.append(send)
        let row = UIStackView(arrangedSubviews: rowItems)
        row.axis = .horizontal
        row.alignment = .bottom
        row.spacing = 8
        guard !suggestions.isEmpty else { return row }
        var arranged: [UIView] = []
        var lastGroup: String?
        for suggestion in suggestions {
            if suggestion.group != lastGroup {
                let header = UILabel()
                header.text = suggestion.group
                header.font = .preferredFont(forTextStyle: .footnote)
                header.textColor = DLUIKitColor.secondaryLabel
                header.adjustsFontForContentSizeCategory = true
                arranged.append(header)
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
            button.addAction(UIAction { [weak self] _ in self?.onSuggestion?(suggestion.id) }, for: .touchUpInside)
            button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
            arranged.append(button)
        }
        arranged.append(row)
        let stack = UIStackView(arrangedSubviews: arranged)
        stack.axis = .vertical
        stack.alignment = .fill
        stack.spacing = 4
        return stack
    }
}
