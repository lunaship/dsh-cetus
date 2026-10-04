import DLUI
import SnapshotTesting
import SwiftUI
import UIKit
import XCTest

@testable import DeepLinks

@MainActor
final class PairingSnapshotTests: XCTestCase {
    override func setUp() {
        super.setUp()
        isRecording = ProcessInfo.processInfo.environment["RECORD_SNAPSHOTS"] == "1"
    }

    func testWelcome() { matrix("1_2_welcome") { PairingWelcomePage() } }
    func testSubmitting() { fixture("1_2_submitting") { PairingWelcomePage(busy: .submitting) } }
    func testPhotoReading() { fixture("1_2_photoReading") { PairingWelcomePage(busy: .photoReading) } }
    func testLANExplanation() { matrix("1_lan_explain") { LocalNetworkExplanationPage() } }
    func testScan() { scannerMatrix(hint: nil, scene: "1_3_scan") }
    func testInvalidScan() { scannerMatrix(hint: .invalidQR, scene: "1_3_scan_invalid") }

    func testPending() {
        matrix("1_5_pending") {
            PairingPendingPage(computerName: PairingFixtures.host.name, deviceName: PairingFixtures.deviceName)
        }
    }

    func testPendingTailscale() {
        fixture("1_5_pending_tailscale") {
            PairingPendingPage(
                computerName: PairingFixtures.host.name, deviceName: PairingFixtures.deviceName, viaTailscale: true)
        }
    }

    func testPendingCleanupFailed() {
        fixture("1_5_pending_cleanupFailed") {
            PairingPendingPage(
                computerName: PairingFixtures.host.name, deviceName: PairingFixtures.deviceName, cleanupFailed: true)
        }
    }

    func testFailureStates() {
        let cases: [(String, PairingFailure)] = [
            ("network", PairingFailure(.transport(.cannotConnectToHost))),
            ("expired", PairingFailure(.qrExpired)),
            ("certificate", PairingFailure(.certificateChanged)),
            ("missingPin", PairingFailure(.fingerprintRequired)),
            ("rejected", .rejected), ("cameraDenied", .cameraDenied), ("cameraUnavailable", .cameraUnavailable),
            ("invalidQR", .invalidQR), ("noPhotoQR", .noPhotoQR),
            ("rateLimit", PairingFailure(.http(code: .tooManyAttempts, status: 429, hint: nil))),
            ("unsupported", PairingFailure(.http(code: .badRequest, status: 415, hint: "ignored"))),
            ("hostHint", PairingFailure(.http(code: .other, status: 418, hint: "Demo host: pairing unavailable"))),
            ("storage", PairingFailure(.storage)),
        ]
        for (scene, failure) in cases {
            if scene == "network" {
                matrix("1_6_fail_\(scene)") { PairingFailurePage(failure: failure) }
            } else {
                fixture("1_6_fail_\(scene)") { PairingFailurePage(failure: failure) }
            }
        }
    }

    /// Render the real SwiftUI alert / rename sheet, with fixture services and a window for modal presentation.
    func testSameNameAndRename() async {
        for rename in [false, true] {
            for language in ["zh-Hans", "en"] {
                let model = await PairingFixtures.conflictModel()
                if rename { model.chooseNewName() }
                let view = PairingFlowView(model: model)
                    .environment(\.locale, Locale(identifier: language))
                    .environment(\.dynamicTypeSize, DynamicTypeSize.large)
                let controller = UIHostingController(rootView: view)
                let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 402, height: 874))
                window.overrideUserInterfaceStyle = .light
                controller.traitOverrides.preferredContentSizeCategory = UIContentSizeCategory.large
                window.rootViewController = controller
                window.makeKeyAndVisible()
                controller.view.layoutIfNeeded()
                // System alert presentation is asynchronous; this delay is only for rendering.
                try? await Task.sleep(for: .milliseconds(300))
                XCTAssertNotNil(controller.presentedViewController, "The fixture dialog must be presented")
                let scene = rename ? "1_2_rename" : "1_2_sameName"
                let traits = UITraitCollection(traitsFrom: [
                    UITraitCollection(userInterfaceStyle: .light),
                    UITraitCollection(userInterfaceIdiom: .phone),
                    UITraitCollection(preferredContentSizeCategory: UIContentSizeCategory.large),
                ])
                assertSnapshot(
                    of: window,
                    as: .image(size: window.bounds.size, traits: traits),
                    named: "default",
                    testName: snapshotName(scene, appearance: .light, language: language))
                window.isHidden = true
                window.rootViewController = nil
            }
        }
    }

    private func scannerMatrix(hint: PairingText?, scene: String) {
        if hint == nil {
            matrix(scene, navigation: false) {
                PairingScannerPage(camera: DLColor.groupedBackground, hint: hint)
            }
        } else {
            fixture(scene, navigation: false) {
                PairingScannerPage(camera: DLColor.groupedBackground, hint: hint)
            }
        }
    }

    private func matrix<V: View>(_ scene: String, navigation: Bool = true, make: () -> V) {
        for language in ["zh-Hans", "en"] {
            for appearance in [UIUserInterfaceStyle.light, .dark] {
                for large in [false, true] {
                    render(
                        scene, appearance: appearance, language: language, large: large,
                        navigation: navigation, make: make)
                }
            }
        }
        // Reduce Transparency is read-only in SwiftUI; DLUI has no override for the system glass styles.
    }

    private func fixture<V: View>(_ scene: String, navigation: Bool = true, make: () -> V) {
        render(
            scene, appearance: .light, language: "zh-Hans", large: false,
            navigation: navigation, make: make)
    }

    private func render<V: View>(
        _ scene: String, appearance: UIUserInterfaceStyle, language: String, large: Bool,
        navigation: Bool, make: () -> V
    ) {
        let content = Group {
            if navigation { NavigationStack { make() } } else { make() }
        }
        .environment(\.locale, Locale(identifier: language))
        .environment(\.dynamicTypeSize, large ? DynamicTypeSize.accessibility3 : DynamicTypeSize.large)
        .tint(DLColor.accent)
        let traits = UITraitCollection(traitsFrom: [
            UITraitCollection(userInterfaceStyle: appearance),
            UITraitCollection(userInterfaceIdiom: .phone),
            UITraitCollection(
                preferredContentSizeCategory: large
                    ? UIContentSizeCategory.accessibilityExtraLarge : UIContentSizeCategory.large),
        ])
        assertSnapshot(
            of: content,
            as: .image(layout: .fixed(width: 402, height: 874), traits: traits),
            named: large ? "large" : "default",
            testName: snapshotName(scene, appearance: appearance, language: language))
    }

    private func snapshotName(_ scene: String, appearance: UIUserInterfaceStyle, language: String) -> String {
        "Snapshot_\(scene)_\(appearance == .dark ? "dark" : "light")_\(language == "en" ? "en" : "zh")"
    }
}
