import SnapshotTesting
import SwiftUI
import UIKit
import XCTest

@testable import DeepLinks

@MainActor final class SettingsSnapshotTests: XCTestCase {
    nonisolated override func invokeTest() {
        withSnapshotTesting(record: snapshotRecordMode()) {
            super.invokeTest()
        }
    }

    func testHome() { shot("7_1_settings") { home } }
    func testComputer() { shot("7_2_computer") { detail(.computer) } }
    func testDiagnostics() { shot("7_3_diagnostics") { detail(.diagnostics) } }
    func testNotifications() { shot("7_4_notifications") { detail(.notifications) } }
    func testAppearance() { shot("7_5_appearance") { detail(.appearance) } }
    func testAbout() { shot("7_13_about") { detail(.about) } }
    func testCrash() { shot("7_15_crash") { detail(.crash) } }

    private func detail(_ page: SettingsPage) -> some View {
        NavigationStack { SettingsDetailPage(page: page) }
            .environment(\.locale, Locale(identifier: "zh-Hans"))
            .transaction { $0.disablesAnimations = true }
    }

    private var home: some View {
        NavigationStack {
            SettingsHomePage(computerName: "MacBook Pro", computerAddress: "192.0.2.10:18640", online: true)
        }
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
        guard let scene else { fatalError("settings snapshots need a window scene") }
        let previousKey = scene.windows.first { $0.isKeyWindow }
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(origin: .zero, size: size)
        window.rootViewController = host
        window.isHidden = false
        window.makeKeyAndVisible()
        host.view.layoutIfNeeded()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            window.windowScene = nil
            previousKey?.makeKey()
        }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 3
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            host.view.drawHierarchy(in: CGRect(origin: .zero, size: size), afterScreenUpdates: true)
        }
    }
}
