import UIKit

final class DLGlassBar: UIView {
    private let effectView: UIVisualEffectView

    override init(frame: CGRect) {
        let glass = UIGlassEffect(style: .regular)
        glass.isInteractive = false
        effectView = UIVisualEffectView(effect: glass)
        super.init(frame: frame)
        effectView.translatesAutoresizingMaskIntoConstraints = false
        addSubview(effectView)
        NSLayoutConstraint.activate([
            effectView.leadingAnchor.constraint(equalTo: leadingAnchor),
            effectView.trailingAnchor.constraint(equalTo: trailingAnchor),
            effectView.topAnchor.constraint(equalTo: topAnchor),
            effectView.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
    }

    required init?(coder: NSCoder) {
        return nil
    }

    /// 截图不用实时玻璃，避免两次录制对不齐。
    func useSolidSnapshotBackground(_ solid: Bool) {
        if solid {
            effectView.effect = nil
            effectView.backgroundColor = .secondarySystemGroupedBackground
            // 截图里的实色替身也要看得出是一块卡：连续圆角 + 轻阴影（浅色下底色与页面同为白色）。
            effectView.layer.cornerRadius = 24
            effectView.layer.cornerCurve = .continuous
            effectView.clipsToBounds = true
            layer.shadowColor = UIColor.black.cgColor
            layer.shadowOpacity = 0.12
            layer.shadowRadius = 12
            layer.shadowOffset = CGSize(width: 0, height: 4)
        } else if effectView.effect == nil {
            let glass = UIGlassEffect(style: .regular)
            glass.isInteractive = false
            effectView.effect = glass
            effectView.backgroundColor = nil
            effectView.layer.cornerRadius = 0
            effectView.clipsToBounds = false
            layer.shadowOpacity = 0
        }
    }

    func install(_ content: UIView, animated: Bool) {
        let holder = effectView.contentView
        let previous = holder.subviews
        content.translatesAutoresizingMaskIntoConstraints = false
        let apply = {
            for view in previous {
                view.removeFromSuperview()
            }
            holder.addSubview(content)
            NSLayoutConstraint.activate([
                content.leadingAnchor.constraint(equalTo: holder.leadingAnchor, constant: 12),
                content.trailingAnchor.constraint(equalTo: holder.trailingAnchor, constant: -12),
                content.topAnchor.constraint(equalTo: holder.topAnchor, constant: 12),
                content.bottomAnchor.constraint(equalTo: holder.bottomAnchor, constant: -12),
            ])
            self.layoutIfNeeded()
        }
        if animated, !UIAccessibility.isReduceMotionEnabled {
            UIView.animate(
                withDuration: 0.35,
                delay: 0,
                usingSpringWithDamping: 0.86,
                initialSpringVelocity: 0.2,
                animations: apply
            )
        } else {
            apply()
        }
    }
}

@MainActor
func dlBarButton(
    title: String,
    prominent: Bool,
    enabled: Bool,
    action: @escaping () -> Void
) -> UIButton {
    // 设计稿 4.3 / 4.4：决策栏是一整块玻璃，里面的按钮全部用实色。
    // 次按钮（拒绝 / 跳过 / 上一题）是灰色填充，主按钮是品牌实心；每屏最多一个品牌实心。
    var config = prominent ? UIButton.Configuration.borderedProminent() : UIButton.Configuration.gray()
    config.title = title
    config.cornerStyle = .capsule
    config.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: 12, bottom: 8, trailing: 12)
    if !prominent {
        config.baseForegroundColor = DLUIKitColor.label
    }
    config.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
        var outgoing = incoming
        outgoing.font = UIFont.preferredFont(forTextStyle: .headline)
        return outgoing
    }
    let button = UIButton(configuration: config, primaryAction: UIAction { _ in action() })
    button.isEnabled = enabled
    if prominent {
        button.tintColor = DLUIKitColor.brandFill
    }
    button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
    return button
}
