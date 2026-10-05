import SnapshotTesting
import SwiftUI
import UIKit
import XCTest

@testable import DeepLinks

@MainActor final class ChatSheetSnapshotTests: XCTestCase {
    nonisolated override func invokeTest() {
        withSnapshotTesting(record: snapshotRecordMode()) {
            super.invokeTest()
        }
    }

    func testPalette() { shot("5_1_palette") { screen(.palette) } }
    func testModel() { shot("5_2_model") { screen(.model) } }
    func testPermission() { shot("5_3_permission") { screen(.permission) } }
    func testFullAccess() { shot("5_4_full_access") { screen(.confirmFull) } }
    func testAttach() { shot("5_5_attach") { screen(.attach) } }
    func testUsage() { shot("5_7_usage") { screen(.usage) } }
    func testAgents() { shot("5_8_agents") { screen(.agents) } }
    func testShare() { shot("5_9_share") { screen(.share) } }
    func testRename() { shot("5_10_rename") { screen(.rename) } }
    func testDelete() { shot("5_11_delete") { screen(.delete) } }
    func testSchedule() { shot("5_12_schedule") { screen(.schedule) } }
    func testGoal() { shot("5_13_goal") { screen(.goal) } }
    func testSelectText() { shot("5_14_select") { screen(.selectText) } }

    private func screen(_ surface: ChatSurface) -> some View {
        NavigationStack { ChatSurfaceScreen(surface: surface) }
            .environment(\.locale, Locale(identifier: "zh-Hans"))
            .transaction { $0.disablesAnimations = true }
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
        guard let scene else { fatalError("sheet snapshots need a window scene") }
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
