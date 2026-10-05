import SnapshotTesting
import SwiftUI
import UIKit
import XCTest

@testable import DeepLinks

@MainActor final class ReviewSnapshotTests: XCTestCase {
    nonisolated override func invokeTest() {
        withSnapshotTesting(record: snapshotRecordMode()) {
            super.invokeTest()
        }
    }

    func testChanges() { shot("6_1_changes") { page(.changes) } }
    func testDiff() { shot("6_2_diff") { page(.diff) } }
    func testFiles() { shot("6_3_files") { page(.files) } }
    func testFile() { shot("6_4_file") { page(.file) } }
    func testPreview() { shot("6_5_preview") { page(.preview) } }
    func testPreviewEmpty() { shot("6_6_preview_empty") { page(.previewEmpty) } }

    private func page(_ surface: ReviewSurface) -> some View {
        NavigationStack { ReviewScreen(surface: surface) }
            .environment(\.locale, Locale(identifier: "zh-Hans"))
            .transaction { $0.disablesAnimations = true }
    }

    private func shot<V: View>(_ name: String, make: () -> V) {
        let image = render(make())
        // 玻璃导航栏的抗锯齿每次差 1–2 个色阶。99.5% 放过这种亚像素，缺一行内容仍会失败。
        assertSnapshot(
            of: image, as: .image(precision: 0.995), named: "default", testName: "Snapshot_\(name)_light_zh")
    }

    private func render<V: View>(_ view: V) -> UIImage {
        let size = CGSize(width: 402, height: 874)
        let host = UIHostingController(rootView: view)
        host.view.frame = CGRect(origin: .zero, size: size)
        host.view.backgroundColor = .systemBackground
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        guard let scene else { fatalError("review snapshots need a window scene") }
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
