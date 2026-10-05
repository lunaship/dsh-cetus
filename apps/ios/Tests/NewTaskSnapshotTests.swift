import DLModels
import SnapshotTesting
import SwiftUI
import UIKit
import XCTest

@testable import DeepLinks

@MainActor final class NewTaskSnapshotTests: XCTestCase {
    nonisolated override func invokeTest() {
        withSnapshotTesting(record: snapshotRecordMode()) {
            super.invokeTest()
        }
    }

    func testDraft() { shot("3_1_draft") { page(sheet: nil) } }
    func testWorkspace() { shot("3_2_workspace") { page(sheet: .workspaces, query: "/tmp/notes") } }
    func testAdd() { shot("3_3_add_ws") { page(sheet: .add, pending: true) } }
    func testPreset() { shot("3_4_preset") { page(sheet: .presets) } }

    private func page(sheet: NewTaskSheet?, query: String = "", pending: Bool = false) -> some View {
        NavigationStack {
            NewTaskPage(
                hostID: "snapshot",
                starter: "把发布说明写短一点",
                workspaces: [WorkspaceInfo(workspaceId: "app", path: "/src/app", title: "app")],
                presets: [AgentPreset(id: "coder", name: "编码", description: "改代码", isDefault: true)],
                staticSnapshot: true,
                snapshotSheet: sheet,
                snapshotQuery: query,
                snapshotPending: pending)
        }
        .environment(\.locale, Locale(identifier: "zh-Hans"))
    }

    private func shot<V: View>(_ name: String, make: () -> V) {
        let image = render(make())
        assertSnapshot(of: image, as: .image, named: "default", testName: "Snapshot_\(name)_light_zh")
    }

    private func render<V: View>(_ view: V) -> UIImage {
        let size = CGSize(width: 402, height: 874)
        let host = UIHostingController(rootView: view)
        host.view.frame = CGRect(origin: .zero, size: size)
        host.view.backgroundColor = .systemBackground
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        guard let scene else { fatalError("new task snapshots need a window scene") }
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(origin: .zero, size: size)
        window.rootViewController = host
        window.isHidden = false
        window.makeKeyAndVisible()
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
