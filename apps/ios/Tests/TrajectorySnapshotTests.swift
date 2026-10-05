import DLModels
import SnapshotTesting
import SwiftUI
import UIKit
import XCTest

@testable import DeepLinks

@MainActor final class TrajectorySnapshotTests: XCTestCase {
    nonisolated override func invokeTest() {
        withSnapshotTesting(record: snapshotRecordMode()) {
            super.invokeTest()
        }
    }

    func testTrace() {
        let page = NavigationStack {
            TrajectoryPage(messages: [
                HistoryMessage(id: "u", role: "user", text: "把发布说明写短一点", turn: 1, time: 1_700_000_000),
                HistoryMessage(
                    id: "t", role: "tool_call", name: "read", args: #"{"path":"Notes.md"}"#, turn: 1,
                    time: 1_700_000_040),
                HistoryMessage(id: "a", role: "assistant", text: "标题可以再短一点。", turn: 1, time: 1_700_000_080),
            ])
        }
        .environment(\.locale, Locale(identifier: "zh-Hans"))
        let image = render(page)
        assertSnapshot(of: image, as: .image, named: "default", testName: "Snapshot_4_7_trace_light_zh")
    }

    private func render<V: View>(_ view: V) -> UIImage {
        let size = CGSize(width: 402, height: 874)
        let host = UIHostingController(rootView: view)
        host.view.frame = CGRect(origin: .zero, size: size)
        host.view.backgroundColor = .systemBackground
        let scene =
            UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
            ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        guard let scene else { fatalError("trajectory snapshots need a window scene") }
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(origin: .zero, size: size)
        window.rootViewController = host
        window.isHidden = false
        window.makeKeyAndVisible()
        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
        defer {
            window.isHidden = true
            window.rootViewController = nil
        }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 3
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            host.view.drawHierarchy(in: CGRect(origin: .zero, size: size), afterScreenUpdates: true)
        }
    }
}
