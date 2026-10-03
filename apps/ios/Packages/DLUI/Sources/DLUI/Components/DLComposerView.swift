import UIKit

public final class DLComposerView: UIView, UITextViewDelegate {
    public var onSubmit: (() -> Void)?

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

    private enum Mode {
        case composer
        case decision(status: String, question: String, secondaryTitle: String, primaryTitle: String)
    }

    public init(text: String = "", isEnabled: Bool = true) {
        super.init(frame: .zero)
        self.text = text
        self.isEnabled = isEnabled
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
        guard let superview else { return }
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
        animated: Bool
    ) {
        mode = .decision(
            status: status,
            question: question,
            secondaryTitle: secondaryTitle,
            primaryTitle: primaryTitle
        )
        applyMode(animated: animated)
    }

    public func textViewDidChange(_ textView: UITextView) {
        isSyncingText = true
        text = textView.text ?? ""
        isSyncingText = false
    }

    private func applyMode(animated: Bool) {
        let content: UIView
        switch mode {
        case .composer:
            content = makeComposerContent()
        case .decision(let status, let question, let secondaryTitle, let primaryTitle):
            content = makeDecisionContent(
                status: status,
                question: question,
                secondaryTitle: secondaryTitle,
                primaryTitle: primaryTitle,
                enabled: isEnabled,
                onSecondary: {},
                onPrimary: {}
            )
        }
        glass.install(content, animated: animated)
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

        let send = dlBarButton(title: "Send", prominent: true, enabled: isEnabled) { [weak self] in
            self?.onSubmit?()
        }
        send.setContentHuggingPriority(.required, for: .horizontal)
        let row = UIStackView(arrangedSubviews: [field, send])
        row.axis = .horizontal
        row.alignment = .bottom
        row.spacing = 8
        return row
    }
}
