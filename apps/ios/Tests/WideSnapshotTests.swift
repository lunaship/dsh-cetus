import DLCore
import DLModels
import DLSecurity
import DLUI
import SnapshotTesting
import SwiftUI
import UIKit
import XCTest

@testable import DeepLinks

@MainActor final class WideSnapshotTests: XCTestCase {
    nonisolated override func invokeTest() {
        withSnapshotTesting(record: snapshotRecordMode()) {
            super.invokeTest()
        }
    }

    func testPortrait() {
        shot("wide_portrait", size: CGSize(width: 768, height: 1024), regular: true, changes: false)
    }

    func testLandscape() {
        shot("wide_landscape", size: CGSize(width: 1024, height: 768), regular: true, changes: true)
    }

    func testSplitTwoThirds() {
        shot("wide_split_two_thirds", size: CGSize(width: 683, height: 768), regular: true, changes: false)
    }

    func testSplitHalf() {
        shot("wide_split_half", size: CGSize(width: 512, height: 768), regular: true, changes: false)
    }

    func testSplitThird() {
        shot("wide_split_third", size: CGSize(width: 341, height: 768), regular: false, changes: false)
    }

    private func shot(_ scene: String, size: CGSize, regular: Bool, changes: Bool) {
        render(scene, size: size, regular: regular, changes: changes, named: "default")
        render(
            scene, size: size, regular: regular, changes: changes, named: "reduce-transparency",
            reduceTransparency: true)
        render(
            scene, size: size, regular: regular, changes: changes, named: "increase-contrast",
            increaseContrast: true)
    }

    private func render(
        _ scene: String, size: CGSize, regular: Bool, changes: Bool, named: String,
        reduceTransparency: Bool = false, increaseContrast: Bool = false
    ) {
        let model = inbox()
        if regular { model.selectedSessionID = "approve" }
        let page = InboxPage(
            model: model,
            staticSnapshot: true,
            wideSnapshotConversation: regular ? conversation() : nil,
            wideSnapshotChanges: changes
        )
        .environment(\.locale, Locale(identifier: "zh-Hans"))
        .environment(\.colorScheme, .light)
        .environment(\.dynamicTypeSize, DynamicTypeSize.large)
        .environment(\.horizontalSizeClass, regular ? .regular : .compact)
        .environment(\._accessibilityReduceTransparency, reduceTransparency)
        .tint(DLColor.accent)
        .transaction { $0.disablesAnimations = true }
        let image = wideImage(page, size: size, regular: regular, increaseContrast: increaseContrast)
        // 分栏玻璃层每次有大量像素差 1–2 个色阶，字节精度会低于 0.995。
        // 感知精度 0.99 放过这种色差；像素精度仍要求 0.995，缺一列内容会失败。
        assertSnapshot(
            of: image,
            as: .image(precision: 0.995, perceptualPrecision: 0.99),
            named: named,
            testName: "Snapshot_\(scene)_light_zh"
        )
    }

    private func wideImage<V: View>(
        _ view: V, size: CGSize, regular: Bool, increaseContrast: Bool = false
    ) -> UIImage {
        let host = UIHostingController(rootView: view)
        host.view.backgroundColor = .systemBackground
        host.overrideUserInterfaceStyle = .light
        host.traitOverrides.userInterfaceStyle = .light
        host.traitOverrides.preferredContentSizeCategory = .large
        if increaseContrast {
            host.traitOverrides.accessibilityContrast = .high
        }
        host.traitOverrides.userInterfaceIdiom = .pad
        host.traitOverrides.horizontalSizeClass = regular ? .regular : .compact
        host.safeAreaRegions = []
        host.view.frame = CGRect(origin: .zero, size: size)

        let scene =
            UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first {
                $0.activationState == .foregroundActive
            } ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        guard let scene else {
            fatalError("wide snapshots need a window scene")
        }
        let previousKey = scene.windows.first { $0.isKeyWindow }
        let window = WideSnapshotWindow(windowScene: scene)
        window.frame = CGRect(origin: .zero, size: size)
        window.overrideUserInterfaceStyle = .light
        window.traitOverrides.userInterfaceStyle = .light
        window.traitOverrides.accessibilityContrast = increaseContrast ? .high : .unspecified
        window.traitOverrides.preferredContentSizeCategory = .large
        window.rootViewController = host
        window.isHidden = false
        window.makeKeyAndVisible()
        host.view.frame = CGRect(origin: .zero, size: size)
        defer {
            window.isHidden = true
            window.rootViewController = nil
            window.windowScene = nil
            previousKey?.makeKey()
        }

        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
        // Split columns get their frames on a later turn. Measuring the message
        // stream before that stretches it to the whole window and covers the sidebar.
        for _ in 0..<4 {
            RunLoop.current.run(until: Date().addingTimeInterval(0.05))
            host.view.layoutIfNeeded()
            CATransaction.flush()
        }
        if let stream = wideStream(in: host) ?? wideStream(in: host.view) {
            let bounds = stream.view.bounds.size
            let canvas = bounds.width > 1 && bounds.height > 1 ? bounds : size
            stream.layoutForStaticSnapshot(canvas: canvas)
        }
        CATransaction.flush()

        let format = UIGraphicsImageRendererFormat()
        format.scale = window.screen.scale > 0 ? window.screen.scale : 3
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            host.view.drawHierarchy(in: CGRect(origin: .zero, size: size), afterScreenUpdates: true)
        }
    }

    private func wideStream(in controller: UIViewController) -> MessageStreamController? {
        if let stream = controller as? MessageStreamController { return stream }
        for child in controller.children {
            if let stream = wideStream(in: child) { return stream }
        }
        return nil
    }

    private func wideStream(in view: UIView) -> MessageStreamController? {
        if let collection = view as? UICollectionView {
            var responder: UIResponder? = collection
            while let current = responder {
                if let stream = current as? MessageStreamController { return stream }
                responder = current.next
            }
        }
        for subview in view.subviews {
            if let stream = wideStream(in: subview) { return stream }
        }
        return nil
    }
}

private final class WideSnapshotWindow: UIWindow {
    override var safeAreaInsets: UIEdgeInsets { .zero }
}

@MainActor private func inbox() -> InboxModel {
    let suite = "wide-snap-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defaults.removePersistentDomain(forName: suite)
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(secondsFromGMT: 0)!
    let now = 1_780_000_000
    let model = InboxModel(
        hostID: "host-a", service: WideInboxService(), cache: InboxMemoryCache(),
        preferences: InboxPreferences(defaults: defaults), autostart: false,
        now: Date(timeIntervalSince1970: TimeInterval(now)), calendar: calendar)
    model.computerName = "工作室"
    model.link = .online(.local)
    model.workspaces = [WorkspaceInfo(path: "/work/app", sessionIds: ["approve", "run"])]
    model.sessions = [
        SessionSummary(
            sessionId: "approve", title: "等你审批发布", updatedAt: now - 120, running: true, cwd: "/work/app",
            awaitingInput: true),
        SessionSummary(
            sessionId: "run", title: "跑测试", updatedAt: now - 1_200, running: true, cwd: "/work/app"),
    ]
    return model
}

@MainActor private func conversation() -> ConversationModel {
    let directory = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(
        "deeplinks-wide-snapshots", isDirectory: true)
    return ConversationModel(
        hostID: "host-a",
        sessionID: "approve",
        seed: ConversationSeed(title: "等你审批发布", workspace: "/work/app", running: true, step: 2),
        service: WideConversationService(),
        box: TranscriptSnapshotBox(keys: InMemorySecureStore(), directory: directory),
        prepared: PreparedTranscript(
            messages: [
                HistoryMessage(id: "user", role: "user", kind: .user, text: "把发布说明写短一点"),
                HistoryMessage(
                    id: "assistant", role: "assistant", kind: .role("assistant"), text: "我先看一下现有说明。"),
            ],
            running: true),
        autostart: false)
}

private struct WideInboxService: InboxServing {}

private struct WideConversationService: ConversationServing {}
