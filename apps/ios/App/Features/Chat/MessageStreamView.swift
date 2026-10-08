import DLCore
import DLUI
import SwiftUI
import UIKit

struct MessageChrome {
    var copy: ConversationCopy
    var reduceMotion: Bool
    var allowsWeb: Bool
    var images: [String: ChatImageState]
    var staticSnapshot: Bool
    var onToggle: (String) -> Void
    var onCopy: (String) -> Void
    var onRegenerate: (String) -> Void
    var onSuggest: (String) -> Void
    var onViewChanges: (Int?) -> Void
    var onLoadImage: (String) -> Void
    var onSelectText: ((String) -> Void)? = nil
    var changesNamespace: Namespace.ID? = nil
}

/// 让 SwiftUI 侧能命令控制器滚动（C04："回到最新"入口）。
///
/// `UIViewControllerRepresentable` 拿不到控制器实例，所以用一个轻量的
/// 可观察持有者把 `scrollToLatest` 暴露出去。
final class MessageStreamCoordinator: ObservableObject {
    private var controller: MessageStreamController?

    func attach(_ controller: MessageStreamController) {
        self.controller = controller
    }

    func detach() {
        controller = nil
    }

    /// 回到最新并恢复跟随。
    func scrollToLatest() {
        controller?.scrollToLatest()
    }
}

struct MessageStreamView: UIViewControllerRepresentable {
    var rows: [TranscriptRow]
    var chrome: MessageChrome
    var pinsToTail: Bool
    var pumpsFrames: Bool
    var onFrame: () -> Void
    var usesSoftTopEdge = true
    // MARK: - C04 分页（入口在滚动内容顶部，不再是导航下的常驻占位）
    /// 接近顶部时触发一次加载；失败时给就地重试。
    var onReachTop: (() -> Void)? = nil
    var hasOlder = false
    var olderFailed = false
    var loadingOlder = false
    /// C04：上翻保持锚点期间收到了新消息 —— 通知 SwiftUI 显示"回到最新"入口。
    var onNewMessagesWhileHeld: (() -> Void)? = nil
    /// SwiftUI 侧通过它命令"回到最新"（C04）。
    var coordinator: MessageStreamCoordinator? = nil

    func makeUIViewController(context: Context) -> MessageStreamController {
        let controller = MessageStreamController()
        controller.usesSoftTopEdge = usesSoftTopEdge
        controller.cache = RowMeasureCache()
        coordinator?.attach(controller)
        return controller
    }

    static func dismantleUIViewController(_ uiViewController: MessageStreamController, coordinator: ()) {
        // controller 销毁时断开，避免 coordinator 持有已释放的实例。
        _ = uiViewController
    }

    func updateUIViewController(_ controller: MessageStreamController, context: Context) {
        controller.overrideUserInterfaceStyle = context.environment.colorScheme == .dark ? .dark : .light
        controller.traitOverrides.preferredContentSizeCategory = category(context.environment.dynamicTypeSize)
        controller.chrome = chrome
        controller.pinsToTail = pinsToTail
        controller.pumpsFrames = pumpsFrames
        controller.onFrame = onFrame
        controller.onReachTop = onReachTop
        controller.hasOlder = hasOlder
        controller.olderFailed = olderFailed
        controller.loadingOlder = loadingOlder
        controller.onNewMessagesWhileHeld = onNewMessagesWhileHeld
        controller.apply(rows, animated: !chrome.staticSnapshot && !chrome.reduceMotion)
    }

    private func category(_ size: DynamicTypeSize) -> UIContentSizeCategory {
        switch size {
        case .xSmall: .extraSmall
        case .small: .small
        case .medium: .medium
        case .large: .large
        case .xLarge: .extraLarge
        case .xxLarge: .extraExtraLarge
        case .xxxLarge: .extraExtraExtraLarge
        case .accessibility1: .accessibilityMedium
        case .accessibility2: .accessibilityLarge
        case .accessibility3: .accessibilityExtraLarge
        case .accessibility4: .accessibilityExtraExtraLarge
        case .accessibility5: .accessibilityExtraExtraExtraLarge
        @unknown default: .large
        }
    }
}

final class MessageStreamController: UIViewController, UICollectionViewDelegateFlowLayout {
    var chrome = MessageChrome(
        copy: ConversationCopy(locale: .current), reduceMotion: true, allowsWeb: false, images: [:],
        staticSnapshot: true,
        onToggle: { _ in }, onCopy: { _ in }, onRegenerate: { _ in }, onSuggest: { _ in }, onViewChanges: { _ in },
        onLoadImage: { _ in })
    var usesSoftTopEdge = true
    var pinsToTail = false
    var pumpsFrames = false {
        didSet { updatePump() }
    }
    var onFrame: () -> Void = {}
    var cache = RowMeasureCache()

    private var collectionView: UICollectionView!
    private var dataSource: UICollectionViewDiffableDataSource<Int, String>!
    private var rowsByID: [String: TranscriptRow] = [:]
    private var previous: [String: TranscriptRow] = [:]
    private var previousIDs: [String] = []
    private var pendingRows: [TranscriptRow] = []
    private var pendingAnimated = false
    private var chromeStamp = ""
    private var displayLink: CADisplayLink?
    private var didPin = false
    /// C04：用户是否上翻过。上翻期间**不**自动贴底（方案 §8 要求 2）。
    private var tailTracker = TailTracker()
    /// 最近一次应用的消息 ID 顺序，供 `scrollToLatest()` 在 apply 之外定位最后一条。
    private var lastIDs: [String] = []
    // MARK: - C04 分页
    var onReachTop: (() -> Void)? = nil
    var hasOlder = false {
        didSet { updateOlderControl() }
    }
    var olderFailed = false {
        didSet { updateOlderControl() }
    }
    var loadingOlder = false {
        didSet { updateOlderControl() }
    }
    /// C04 要求 5：失败就地重试；加载中给状态。自动分页失败后不再自动重试，只留这个手动入口。
    private let olderButton = UIButton(configuration: .bordered())
    /// C04：上翻保持锚点期间收到了新消息 —— 通知 SwiftUI 显示"回到最新"入口。
    var onNewMessagesWhileHeld: (() -> Void)? = nil
    /// 接近顶部只触发一次，避免连续滚动时重复请求。
    private var didRequestTopPage = false
    private var layingOutSnapshot = false
    private var snapshotHeights: [String: CGFloat] = [:]

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        let item = NSCollectionLayoutItem(
            layoutSize: NSCollectionLayoutSize(
                widthDimension: .fractionalWidth(1), heightDimension: .estimated(44)))
        let group = NSCollectionLayoutGroup.vertical(
            layoutSize: NSCollectionLayoutSize(widthDimension: .fractionalWidth(1), heightDimension: .estimated(44)),
            subitems: [item])
        let section = NSCollectionLayoutSection(group: group)
        section.interGroupSpacing = 4
        section.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: 0, bottom: 16, trailing: 0)
        collectionView = UICollectionView(
            frame: view.bounds, collectionViewLayout: UICollectionViewCompositionalLayout(section: section))
        collectionView.accessibilityIdentifier = "message-stream"
        collectionView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        collectionView.backgroundColor = .systemBackground
        collectionView.keyboardDismissMode = .interactive
        // UIKit-backed message flow uses the same iOS 26 system soft edge as SwiftUI.
        if usesSoftTopEdge { collectionView.topEdgeEffect.style = .soft }
        view.addSubview(collectionView)
        olderButton.translatesAutoresizingMaskIntoConstraints = false
        olderButton.accessibilityIdentifier = "load-older"
        olderButton.addAction(
            UIAction { [weak self] _ in
                guard let self, !self.loadingOlder else { return }
                self.onReachTop?()
            }, for: .touchUpInside)
        view.addSubview(olderButton)
        NSLayoutConstraint.activate([
            olderButton.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            olderButton.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            olderButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 44),
        ])
        updateOlderControl()
        let registration = UICollectionView.CellRegistration<MeasuredCell, String> { [weak self] cell, _, id in
            guard let self, let row = self.rowsByID[id] else { return }
            let chrome = self.chrome
            cell.measureID = id
            cell.measureRevision = transcriptMeasureRevision(row)
            cell.cache = self.cache
            cell.backgroundColor = .clear
            if chrome.staticSnapshot {
                // UIHostingConfiguration stays blank until a later display pass. The screenshot
                // path hosts the same row and lays it out before the image is taken.
                cell.configureSnapshot(
                    MessageRowView(row: row, chrome: chrome), traits: self.traitCollection,
                    contentSizeCategory: self.traitOverrides.preferredContentSizeCategory)
            } else {
                cell.contentConfiguration = UIHostingConfiguration {
                    MessageRowView(row: row, chrome: chrome)
                }
                .margins(.all, 0)
            }
        }
        dataSource = UICollectionViewDiffableDataSource<Int, String>(collectionView: collectionView) {
            collectionView, indexPath, id in
            collectionView.dequeueConfiguredReusableCell(using: registration, for: indexPath, item: id)
        }
        apply(pendingRows, animated: pendingAnimated)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        updatePump()
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        stopPump()
    }

    func apply(_ rows: [TranscriptRow], animated: Bool) {
        pendingRows = rows
        pendingAnimated = animated
        guard isViewLoaded, let dataSource else { return }
        let ids = rows.map(\.id)
        lastIDs = ids
        rowsByID = Dictionary(uniqueKeysWithValues: rows.map { ($0.id, $0) })
        var snapshot = NSDiffableDataSourceSnapshot<Int, String>()
        snapshot.appendSections([0])
        snapshot.appendItems(ids, toSection: 0)
        let changed = ids.filter { previous[$0] != rowsByID[$0] && previous[$0] != nil }
        let structureChanged = ids != previousIDs
        let stamp = Self.stamp(chrome)
        let chromeChanged = stamp != chromeStamp
        chromeStamp = stamp
        let existing = ids.filter { previous[$0] != nil }
        if !chrome.staticSnapshot {
            if chromeChanged, !existing.isEmpty {
                snapshot.reconfigureItems(existing)
            } else if !changed.isEmpty {
                snapshot.reconfigureItems(changed)
            }
        }
        // C04 要求 4：前插更早的消息前记下首个可见消息与它的相对偏移。
        let prepended =
            !chrome.staticSnapshot && !previousIDs.isEmpty && ids.first != previousIDs.first
            && previousIDs.first.map { rowsByID[$0] != nil } == true
        let anchor = prepended ? captureAnchor() : nil
        previous = rowsByID
        previousIDs = ids
        if chrome.staticSnapshot {
            // Reload is synchronous. A diff apply at the snapshot's first zero-size pass
            // remembers the ids and then refuses to create cells once the canvas exists.
            dataSource.applySnapshotUsingReloadData(snapshot)
        } else {
            dataSource.apply(snapshot, animatingDifferences: animated && !changed.isEmpty && !chromeChanged)
        }
        collectionView.layoutIfNeeded()
        if let anchor, restore(anchor) { return }
        guard !chrome.staticSnapshot, pinsToTail else { return }
        // C04：上翻读历史时保持锚点 —— 这是旧实现最大的问题
        // （无条件 scrollToItem 会把用户拽回底部）。
        refreshTailState()
        if tailTracker.policy == .hold {
            // 用户在上翻读历史，期间来了新消息 → 提示，但**不**把他拽回底部。
            if !changed.isEmpty || structureChanged {
                onNewMessagesWhileHeld?()
            }
            return
        }
        guard !didPin || structureChanged || !changed.isEmpty, let last = ids.last,
            let indexPath = dataSource.indexPath(for: last)
        else { return }
        collectionView.scrollToItem(at: indexPath, at: .bottom, animated: false)
        didPin = true
    }

    /// 首个可见消息及其顶部相对可视区的偏移。
    private func captureAnchor() -> ScrollAnchor? {
        guard let collectionView, let dataSource else { return nil }
        let offset = collectionView.contentOffset.y
        let visible = collectionView.indexPathsForVisibleItems.sorted().compactMap {
            path -> (id: String, top: Double, height: Double)? in
            guard let id = dataSource.itemIdentifier(for: path),
                let frame = collectionView.layoutAttributesForItem(at: path)?.frame
            else { return nil }
            return (id, Double(frame.minY - offset), Double(frame.height))
        }
        return AnchorResolver.capture(visible: visible)
    }

    /// 前插后把锚点消息放回原来的位置，用户正在读的那条不跳走。
    private func restore(_ anchor: ScrollAnchor) -> Bool {
        guard let collectionView, let dataSource,
            let path = dataSource.indexPath(for: anchor.messageID),
            let frame = collectionView.layoutAttributesForItem(at: path)?.frame
        else { return false }
        let newTop = Double(frame.minY - collectionView.contentOffset.y)
        let delta = AnchorResolver.compensation(anchor: anchor, newTop: newTop)
        guard abs(delta) > 0.5 else { return true }
        collectionView.contentOffset.y += CGFloat(delta)
        return true
    }

    /// 依据当前滚动位置更新"是否上翻"。
    ///
    /// 只用滚动位置判断会在惯性滚动经过底部时抖动，所以阈值用可视高度的
    /// 比例（见 `TailTracker.tailThresholdFraction`），且进入阈值内才恢复跟随。
    private func refreshTailState() {
        guard let collectionView, collectionView.bounds.height > 1 else { return }
        let visible = collectionView.bounds.height
        let contentHeight = collectionView.contentSize.height
        let distance = max(0, contentHeight - collectionView.contentOffset.y - visible)
        tailTracker.update(distanceFromBottom: Double(distance), visibleHeight: Double(visible))
    }

    /// 用户滚动时同步状态；贴底时清掉"上翻"标记，恢复正常跟随。
    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        refreshTailState()
        maybeLoadOlder()
    }

    /// 接近顶部触发一次分页（方案 §8 要求 5）。失败时保留手动入口，
    /// 由 `olderFailed` 控制是否显示重试。
    private func maybeLoadOlder() {
        guard hasOlder, !loadingOlder, !olderFailed, !didRequestTopPage else { return }
        guard let collectionView, collectionView.contentSize.height > 1 else { return }
        let distanceToTop = collectionView.contentOffset.y
        // 距顶部一屏内就预取，用户滚到顶时内容已就位。
        guard distanceToTop < collectionView.bounds.height * 0.5 else { return }
        didRequestTopPage = true
        onReachTop?()
        // 一次分页完成后解除，允许下一次继续翻页。
        DispatchQueue.main.async { [weak self] in self?.didRequestTopPage = false }
    }

    /// 更早消息入口：失败 → 重试；加载中 → 忙碌态；其余隐藏（自动分页负责）。
    private func updateOlderControl() {
        guard isViewLoaded else { return }
        let visible = hasOlder && !chrome.staticSnapshot && (olderFailed || loadingOlder)
        olderButton.isHidden = !visible
        guard visible else { return }
        var config = UIButton.Configuration.bordered()
        config.cornerStyle = .capsule
        config.showsActivityIndicator = loadingOlder
        config.title = chrome.copy.text(loadingOlder ? .loadOlder : .loadOlderFailed)
        config.subtitle = loadingOlder ? nil : chrome.copy.text(.retry)
        config.image = loadingOlder ? nil : UIImage(systemName: "arrow.clockwise")
        config.imagePadding = 6
        olderButton.configuration = config
        olderButton.isEnabled = !loadingOlder
    }

    /// 显式回到最新（点了"有新内容"入口，或自己发完消息）。
    func scrollToLatest(animated: Bool = true) {
        tailTracker.jumpToLatest()
        didPin = false
        guard let collectionView, let last = lastIDs.last,
            let indexPath = dataSource.indexPath(for: last)
        else { return }
        collectionView.scrollToItem(at: indexPath, at: .bottom, animated: animated)
        didPin = true
    }

    /// Screenshot path only. The snapshot strategy renders before SwiftUI has given this
    /// collection view a canvas, so self-sized hosting cells never materialize.
    func layoutForStaticSnapshot(canvas: CGSize) {
        guard chrome.staticSnapshot, isViewLoaded, let collectionView else { return }
        guard canvas.width > 1, canvas.height > 1, !layingOutSnapshot else { return }
        layingOutSnapshot = true
        defer { layingOutSnapshot = false }
        UIView.performWithoutAnimation {
            collectionView.isPrefetchingEnabled = false
            collectionView.autoresizingMask = []
            if view.bounds.width < 1 || view.bounds.height < 1 {
                view.frame.size = canvas
            }
            // SwiftUI may already have placed this view below the navigation bar.
            // The canvas is only a fallback; using it here would put the tail offscreen.
            collectionView.frame = view.bounds
            let overlap = snapshotTopOverlap()
            collectionView.contentInsetAdjustmentBehavior = .never
            var inset = collectionView.contentInset
            inset.top = overlap
            collectionView.contentInset = inset

            // Measure every row at the final width before scrolling. Offscreen estimated
            // heights otherwise let a tail pin stop in the middle of the last row.
            let measuringCell = MeasuredCell(frame: CGRect(origin: .zero, size: collectionView.bounds.size))
            snapshotHeights = Dictionary(
                uniqueKeysWithValues: pendingRows.map { row in
                    (
                        row.id,
                        measuringCell.configureSnapshot(
                            MessageRowView(row: row, chrome: chrome), traits: traitCollection,
                            contentSizeCategory: traitOverrides.preferredContentSizeCategory)
                    )
                })
            let layout = UICollectionViewFlowLayout()
            layout.estimatedItemSize = .zero
            layout.minimumLineSpacing = 4
            layout.minimumInteritemSpacing = 0
            layout.sectionInset = UIEdgeInsets(top: 8, left: 0, bottom: 16, right: 0)
            collectionView.delegate = self
            collectionView.setCollectionViewLayout(layout, animated: false)
            apply(pendingRows, animated: false)
            collectionView.layoutIfNeeded()

            let top = -collectionView.adjustedContentInset.top
            var offset = top
            if pinsToTail,
                collectionView.contentSize.height + collectionView.adjustedContentInset.top
                    + collectionView.adjustedContentInset.bottom > collectionView.bounds.height,
                let last = pendingRows.last,
                let indexPath = dataSource.indexPath(for: last.id),
                let attributes = collectionView.layoutAttributesForItem(at: indexPath)
            {
                offset = max(
                    top,
                    attributes.frame.maxY - collectionView.bounds.height + collectionView.adjustedContentInset.bottom)
            }
            collectionView.setContentOffset(CGPoint(x: 0, y: offset), animated: false)
            collectionView.layoutIfNeeded()
            for case let cell as MeasuredCell in collectionView.visibleCells {
                cell.attachSnapshotHost(to: self)
                cell.contentView.setNeedsLayout()
                cell.layoutIfNeeded()
            }
        }
    }

    func collectionView(
        _ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout,
        sizeForItemAt indexPath: IndexPath
    ) -> CGSize {
        let id = dataSource.itemIdentifier(for: indexPath)
        return CGSize(width: collectionView.bounds.width, height: id.flatMap { snapshotHeights[$0] } ?? 44)
    }

    private func snapshotTopOverlap() -> CGFloat {
        guard let collectionView, let root = view.window else { return 0 }
        guard let bar = Self.findNavigationBar(in: root), bar.bounds.height > 1 else { return 0 }
        let barFrame = bar.convert(bar.bounds, to: nil)
        let contentFrame = collectionView.convert(collectionView.bounds, to: nil)
        return min(96, max(0, barFrame.maxY - contentFrame.minY))
    }

    private static func findNavigationBar(in view: UIView) -> UINavigationBar? {
        if let bar = view as? UINavigationBar { return bar }
        for subview in view.subviews {
            if let bar = findNavigationBar(in: subview) { return bar }
        }
        return nil
    }

    private static func stamp(_ chrome: MessageChrome) -> String {
        let images = chrome.images.keys.sorted().map { "\($0)=\(String(describing: chrome.images[$0]))" }.joined(
            separator: ",")
        return
            "\(images)|\(chrome.staticSnapshot)|\(chrome.reduceMotion)|\(chrome.allowsWeb)|\(chrome.copy.text(.copy))"
    }

    @objc private func tick() {
        onFrame()
    }

    private func updatePump() {
        if pumpsFrames, view.window != nil {
            guard displayLink == nil else { return }
            let link = CADisplayLink(target: self, selector: #selector(tick))
            link.add(to: .main, forMode: .common)
            displayLink = link
        } else {
            stopPump()
        }
    }

    private func stopPump() {
        displayLink?.invalidate()
        displayLink = nil
    }
}

final class MeasuredCell: UICollectionViewCell {
    var measureID: String?
    var measureRevision = 0
    weak var cache: RowMeasureCache?
    private var snapshotRow: MessageRowView?
    private var snapshotHost: UIHostingController<AnyView>?
    private var snapshotContentSizeCategory: UIContentSizeCategory = .large
    private var placingSnapshot = false

    @discardableResult
    func configureSnapshot(
        _ row: MessageRowView, traits: UITraitCollection, contentSizeCategory: UIContentSizeCategory
    ) -> CGFloat {
        snapshotRow = row
        snapshotContentSizeCategory = contentSizeCategory
        contentConfiguration = nil
        contentView.backgroundColor = .clear
        overrideUserInterfaceStyle = traits.userInterfaceStyle
        traitOverrides.preferredContentSizeCategory = contentSizeCategory
        let width = bounds.width > 1 ? bounds.width : contentView.bounds.width
        if width > 1 {
            return placeSnapshot(row, width: width)
        }
        return 44
    }

    func attachSnapshotHost(to parent: UIViewController) {
        if let snapshotRow, snapshotHost?.view.superview == nil {
            let width = bounds.width > 1 ? bounds.width : 402
            _ = placeSnapshot(snapshotRow, width: width)
        }
        guard let snapshotHost else { return }
        if snapshotHost.parent == nil {
            parent.addChild(snapshotHost)
            snapshotHost.didMove(toParent: parent)
        }
        snapshotHost.view.layoutIfNeeded()
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        snapshotRow = nil
    }

    override func preferredLayoutAttributesFitting(_ layoutAttributes: UICollectionViewLayoutAttributes)
        -> UICollectionViewLayoutAttributes
    {
        if snapshotRow != nil {
            // The snapshot flow layout supplies the full row height, including chip
            // minimum heights and padding. Reentrant hosting measurement can shrink it.
            return layoutAttributes
        }
        let width = Int(layoutAttributes.size.width.rounded())
        if let measureID, let cached = cache?.height(id: measureID, width: width, revision: measureRevision),
            let attributes = layoutAttributes.copy() as? UICollectionViewLayoutAttributes
        {
            attributes.size.height = CGFloat(cached)
            return attributes
        }
        let fitted = super.preferredLayoutAttributesFitting(layoutAttributes)
        if let measureID {
            cache?.store(Double(fitted.size.height), id: measureID, width: width, revision: measureRevision)
        }
        return fitted
    }

    private func placeSnapshot(_ row: MessageRowView, width: CGFloat) -> CGFloat {
        if placingSnapshot { return max(snapshotHost?.view.bounds.height ?? 44, 1) }
        placingSnapshot = true
        defer { placingSnapshot = false }
        let content = AnyView(
            row.tint(DLColor.accent)
                .environment(\.colorScheme, overrideUserInterfaceStyle == .dark ? .dark : .light)
                .environment(\.dynamicTypeSize, snapshotDynamicType(snapshotContentSizeCategory))
                .frame(width: width, alignment: .leading))
        let host: UIHostingController<AnyView>
        if let snapshotHost {
            snapshotHost.rootView = content
            host = snapshotHost
        } else {
            host = UIHostingController(rootView: content)
            host.view.backgroundColor = .clear
            host.safeAreaRegions = []
            host.view.translatesAutoresizingMaskIntoConstraints = true
            host.view.autoresizingMask = []
            contentView.addSubview(host.view)
            snapshotHost = host
        }
        host.overrideUserInterfaceStyle = overrideUserInterfaceStyle
        // An offscreen cell's trait collection may not reflect its overrides yet.
        host.traitOverrides.preferredContentSizeCategory = snapshotContentSizeCategory
        let fitted = host.sizeThatFits(in: CGSize(width: width, height: 10_000))
        let height = fitted.height.isFinite ? min(4_000, max(fitted.height, 1)) : 44
        host.view.frame = CGRect(x: 0, y: 0, width: width, height: height)
        return height
    }
}

private func snapshotDynamicType(_ category: UIContentSizeCategory) -> DynamicTypeSize {
    switch category {
    case .extraSmall: .xSmall
    case .small: .small
    case .medium: .medium
    case .large: .large
    case .extraLarge: .xLarge
    case .extraExtraLarge: .xxLarge
    case .extraExtraExtraLarge: .xxxLarge
    case .accessibilityMedium: .accessibility1
    case .accessibilityLarge: .accessibility2
    case .accessibilityExtraLarge: .accessibility3
    case .accessibilityExtraExtraLarge: .accessibility4
    case .accessibilityExtraExtraExtraLarge: .accessibility5
    default: .large
    }
}
