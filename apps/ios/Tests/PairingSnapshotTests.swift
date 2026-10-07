import DLUI
import SnapshotTesting
import SwiftUI
import UIKit
import XCTest

@testable import Cetus

@MainActor
final class PairingSnapshotTests: XCTestCase {
    /// Task-local. `setUp` returns before the test method, so it cannot hold record mode.
    nonisolated override func invokeTest() {
        withSnapshotTesting(record: snapshotRecordMode()) {
            super.invokeTest()
        }
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

    /// Snapshot content directly; presenting system modals crashes the CI simulator's keyboard event handling.
    func testSameNameAndRename() {
        for language in ["zh-Hans", "en"] {
            render(
                "1_2_sameName", appearance: .light, language: language, large: false, navigation: false
            ) {
                PairingConflictSnapshotContent()
            }
            if language == "zh-Hans" {
                accessibility("1_2_sameName", navigation: false) { PairingConflictSnapshotContent() }
            }
            render(
                "1_2_rename", appearance: .light, language: language, large: false, navigation: true
            ) {
                PairingRenameForm(
                    newName: .constant(PairingFixtures.deviceName), originalName: PairingFixtures.deviceName,
                    allowsFocus: false)
            }
            if language == "zh-Hans" {
                accessibility("1_2_rename", navigation: true) {
                    PairingRenameForm(
                        newName: .constant(PairingFixtures.deviceName), originalName: PairingFixtures.deviceName,
                        allowsFocus: false)
                }
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
        // The public accessibilityReduceTransparency key path is get-only; these variants use the private environment value.
        accessibility(scene, navigation: navigation, make: make)
    }

    private func fixture<V: View>(_ scene: String, navigation: Bool = true, make: () -> V) {
        render(
            scene, appearance: .light, language: "zh-Hans", large: false,
            navigation: navigation, make: make)
        accessibility(scene, navigation: navigation, make: make)
    }

    private func accessibility<V: View>(_ scene: String, navigation: Bool, make: () -> V) {
        render(
            scene, appearance: .light, language: "zh-Hans", large: false, navigation: navigation,
            reduceTransparency: true, make: make)
        render(
            scene, appearance: .light, language: "zh-Hans", large: false, navigation: navigation,
            increaseContrast: true, make: make)
    }

    private func render<V: View>(
        _ scene: String, appearance: UIUserInterfaceStyle, language: String, large: Bool,
        navigation: Bool, reduceTransparency: Bool = false, increaseContrast: Bool = false, make: () -> V
    ) {
        let content = Group {
            if navigation { NavigationStack { make() } } else { make() }
        }
        .environment(\.locale, Locale(identifier: language))
        .environment(\.colorScheme, appearance == .dark ? .dark : .light)
        .environment(\.dynamicTypeSize, large ? DynamicTypeSize.accessibility3 : DynamicTypeSize.large)
        .environment(\._accessibilityReduceTransparency, reduceTransparency)
        .tint(DLColor.accent)
        var traits = [
            UITraitCollection(userInterfaceStyle: appearance),
            UITraitCollection(userInterfaceIdiom: .phone),
            UITraitCollection(
                preferredContentSizeCategory: large
                    ? UIContentSizeCategory.accessibilityExtraLarge : UIContentSizeCategory.large),
        ]
        if increaseContrast {
            traits.append(UITraitCollection(accessibilityContrast: .high))
        }
        let variant = variantName(
            large: large, reduceTransparency: reduceTransparency, increaseContrast: increaseContrast)
        // WelcomeSnapshotTests owns the plain welcome accessibility names. Pairing keeps its
        // existing matrix names but adds a suffix so both resources can enter one test bundle.
        let name =
            scene == "1_2_welcome" && (reduceTransparency || increaseContrast)
            ? "Snapshot_1_2_pairing_welcome_light_zh" : snapshotName(scene, appearance: appearance, language: language)
        assertSnapshot(
            of: content,
            as: .image(layout: .fixed(width: 402, height: 874), traits: UITraitCollection(traitsFrom: traits)),
            named: variant,
            testName: name)
    }

    private func variantName(large: Bool, reduceTransparency: Bool, increaseContrast: Bool) -> String {
        if reduceTransparency { return "reduce-transparency" }
        if increaseContrast { return "increase-contrast" }
        return large ? "large" : "default"
    }

    private func snapshotName(_ scene: String, appearance: UIUserInterfaceStyle, language: String) -> String {
        "Snapshot_\(scene)_\(appearance == .dark ? "dark" : "light")_\(language == "en" ? "en" : "zh")"
    }
}

/// Test-only static representation of the production alert's localized content and choices.
private struct PairingConflictSnapshotContent: View {
    @Environment(\.locale) private var locale

    var body: some View {
        let copy = PairingCopy(locale: locale)
        VStack(spacing: 24) {
            Text(copy.text(.sameNameTitle)).font(.title3.bold())
            Text(copy.text(.sameNameBody)).foregroundStyle(DLColor.secondaryLabel)
            VStack(spacing: 16) {
                Button(copy.text(.replace), role: .destructive) {}
                Button(copy.text(.rename)) {}
                Button(copy.text(.cancel), role: .cancel) {}
            }
        }
        .multilineTextAlignment(.center)
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(DLColor.background)
    }
}
