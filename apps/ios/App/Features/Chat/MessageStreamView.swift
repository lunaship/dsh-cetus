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
}

struct MessageStreamView: UIViewControllerRepresentable {
    var rows: [TranscriptRow]
    var chrome: MessageChrome
    var pinsToTail: Bool
    var pumpsFrames: Bool
    var onFrame: () -> Void

    func makeUIViewController(context: Context) -> MessageStreamController {
        let controller = MessageStreamController()
        controller.cache = RowMeasureCache()
        return controller
    }

    func updateUIViewController(_ controller: MessageStreamController, context: Context) {
        controller.overrideUserInterfaceStyle = context.environment.colorScheme == .dark ? .dark : .light
        controller.traitOverrides.preferredContentSizeCategory = category(context.environment.dynamicTypeSize)
        controller.chrome = chrome
        controller.pinsToTail = pinsToTail
        controller.pumpsFrames = pumpsFrames
        controller.onFrame = onFrame
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

final class MessageStreamController: UIViewController {
    var chrome = MessageChrome(
        copy: ConversationCopy(locale: .current), reduceMotion: true, allowsWeb: false, images: [:],
        staticSnapshot: true,
        onToggle: { _ in }, onCopy: { _ in }, onRegenerate: { _ in }, onSuggest: { _ in }, onViewChanges: { _ in },
        onLoadImage: { _ in })
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
        collectionView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        collectionView.backgroundColor = .systemBackground
        collectionView.keyboardDismissMode = .interactive
        view.addSubview(collectionView)
        let registration = UICollectionView.CellRegistration<MeasuredCell, String> { [weak self] cell, _, id in
            guard let self, let row = self.rowsByID[id] else { return }
            let chrome = self.chrome
            cell.measureID = id
            cell.measureRevision = transcriptMeasureRevision(row)
            cell.cache = self.cache
            cell.backgroundColor = .clear
            cell.contentConfiguration = UIHostingConfiguration {
                MessageRowView(row: row, chrome: chrome)
            }
            .margins(.all, 0)
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
        if chromeChanged, !existing.isEmpty {
            snapshot.reconfigureItems(existing)
        } else if !changed.isEmpty {
            snapshot.reconfigureItems(changed)
        }
        previous = rowsByID
        previousIDs = ids
        dataSource.apply(snapshot, animatingDifferences: animated && !changed.isEmpty && !chromeChanged)
        collectionView.layoutIfNeeded()
        guard pinsToTail, !didPin || structureChanged || !changed.isEmpty, let last = ids.last,
            let indexPath = dataSource.indexPath(for: last)
        else { return }
        collectionView.scrollToItem(at: indexPath, at: .bottom, animated: false)
        didPin = true
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

    override func preferredLayoutAttributesFitting(_ layoutAttributes: UICollectionViewLayoutAttributes)
        -> UICollectionViewLayoutAttributes
    {
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
}
