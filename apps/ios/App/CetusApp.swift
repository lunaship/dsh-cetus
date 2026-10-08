import DLCore
import DLNet
import DLSecurity
import SwiftUI
import UIKit

@MainActor
struct RootView: View {
    let pairing: PairingFlowModel
    var screenshots = false
    @State private var selectedHostId: String?
    /// 遮罩是何时盖上的。用于判断「卡住」并给出手动出口（C13 要求 6）。
    @State private var coveredSince: Date?
    /// 用户手动解除后，本次前台周期内不再重盖（避免一解除就被重新盖住）。
    @State private var escapedThisCycle = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        content
            // 草稿仓库从依赖根注入一次，紧凑导航 / 宽屏详情 / 分享预填三条路
            // 都取到同一份（C02 要求 4）。测试可用 .composerDraftStore(_:) 覆盖。
            .composerDraftStore(ComposerDraftStore.live(keys: KeychainStore()))
            .overlay {
                if showsCover {
                    PrivacyCoverView(
                        canEscape: PrivacyCover.canEscapeManually(coveredSince: coveredSince, now: now),
                        onEscape: {
                            escapedThisCycle = true
                            coveredSince = nil
                        }
                    )
                    // 显式标识，便于 UI 测试断言「遮罩已解除」
                    .accessibilityIdentifier("privacy-cover")
                }
            }
            .onChange(of: scenePhase) { _, phase in
                let next = visibility(for: phase)
                if PrivacyCover.shouldRelease(next, screenshots: screenshots) {
                    // 回到前台：**一定**解除，避免截图保护变成永久白屏
                    coveredSince = nil
                    escapedThisCycle = false
                } else if coveredSince == nil {
                    coveredSince = Date()
                }
            }
    }

    /// 供 `PrivacyCoverView` 判断是否已卡住；用 `Date()` 而非 TimelineView 以保持简单。
    private var now: Date { Date() }

    private var showsCover: Bool {
        PrivacyCover.covers(visibility, screenshots: screenshots) && !escapedThisCycle
    }

    @ViewBuilder private var content: some View {
        if let selectedHostId {
            InboxFlowView(
                hostID: selectedHostId,
                onMissing: { self.selectedHostId = nil },
                onSwitch: { self.selectedHostId = $0 }
            )
            .id(selectedHostId)
        } else {
            PairingFlowView(model: pairing, onPaired: { selectedHostId = $0 })
                .task {
                    if selectedHostId == nil, let host = pairing.pairedHost {
                        selectedHostId = host.hostId
                    }
                }
        }
    }

    private var visibility: AppVisibility { visibility(for: scenePhase) }

    private func visibility(for phase: ScenePhase) -> AppVisibility {
        switch phase {
        case .active: .active
        case .background: .background
        default: .inactive
        }
    }
}

/// App 切换器隐私遮罩（C13 要求 6）。
///
/// 设计要点：
/// - 默认是**纯遮挡**，不泄漏任何页面内容（也不用模糊，模糊可被反推）。
/// - 卡住超过 `PrivacyCover.manualEscapeAfter` 后给出手动出口 —— 否则一旦
///   `scenePhase` 没能自己回到 active，用户面对的就是一块**无法消除的白屏**，
///   只能杀 App。这是「不能让截图保护变成永久白屏」的兜底。
@MainActor
struct PrivacyCoverView: View {
    var canEscape: Bool
    var onEscape: () -> Void

    var body: some View {
        ZStack {
            Rectangle().fill(.background).ignoresSafeArea()
            if canEscape {
                VStack(spacing: 12) {
                    Text(String(localized: "cetus 正在后台"))
                        .font(.headline)
                    Button(String(localized: "继续使用"), action: onEscape)
                        .buttonStyle(.borderedProminent)
                }
                .padding(24)
            }
        }
        // 未到可解除时间时不吃事件，保持与「纯遮挡」一致的行为
        .allowsHitTesting(canEscape)
    }
}

final class CetusAppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // DEBUG-only: seed the shared Keychain for the simctl NSE check.
        DebugPushSimSeed.applyIfRequested()
        let open = UNNotificationAction(
            identifier: PushNotificationCategory.openAction,
            title: "Open",
            options: [.foreground])
        let category = UNNotificationCategory(
            identifier: PushNotificationCategory.identifier,
            actions: [open],
            intentIdentifiers: [],
            options: [])
        UNUserNotificationCenter.current().setNotificationCategories([category])
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        defer { completionHandler() }
        guard
            response.actionIdentifier == UNNotificationDefaultActionIdentifier
                || response.actionIdentifier == PushNotificationCategory.openAction,
            // The APNs payload carries only `e`/`k`, so the reader rebuilds the
            // routing ids locally: `deviceId` from the kid binding, `sessionId`
            // by opening the ciphertext. This only navigates; it never approves.
            let request = PushPayloadReader.openRequest(
                in: response.notification.request.content.userInfo, bindings: PushKeyStore.live())
        else { return }
        NotificationCenter.default.post(
            name: .deepLinksOpenPush,
            object: nil,
            userInfo: ["deviceId": request.deviceID, "sessionId": request.sessionID])
    }

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        let changed = PushTokenBridge.shared.didRegister(deviceToken: deviceToken)
        // RFC 0002 §16.2.1: a changed token must be re-registered with the plugin.
        // The settings screen performs the actual sync on next appearance; this
        // notification lets an already-open screen react immediately.
        if changed {
            NotificationCenter.default.post(name: .deepLinksPushTokenChanged, object: nil)
        }
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        PushTokenBridge.shared.didFail(error)
    }
}

@main @MainActor
struct CetusApp: App {
    @UIApplicationDelegateAdaptor(CetusAppDelegate.self) private var delegate
    @State private var pairing: PairingFlowModel

    init() {
        let environment = ProcessInfo.processInfo.environment
        // App-hosted tests must not load a developer's paired hosts or poll a real computer.
        // UI tests also set XCTestConfigurationFilePath, but they pass -e2eQRPayload and must stay live.
        let testing = environment["XCTestConfigurationFilePath"] != nil || environment["XCTestBundlePath"] != nil
        if !testing { WorkspaceFileExport.expire() }
        let endToEnd = DebugE2EQRLaunch.isRequested
        let performance = PerformanceLaunchFixture.isRequested
        let model = PairingFlowModel(
            services: testing && !endToEnd && !performance
                ? .offline
                : .live(
                    store: performance || PerformanceLaunchFixture.unsignedStorage != nil
                        ? PerformanceLaunchFixture.hostStore() : HostStore()),
            gate: LocalNetworkPermissionGate(), deviceName: UIDevice.current.name)
        #if DEBUG
            // Same entry as a successful scan. Runs before restore(), which will not overwrite it.
            if let payload = DebugE2EQRLaunch.payloadText() {
                model.receive(payload)
            }
        #endif
        _pairing = State(initialValue: model)
    }

    var body: some Scene {
        WindowGroup { RootView(pairing: pairing) }
    }
}

/// Debug-only stand-in for a successful scan. Release builds compile this reader out.
/// The launch argument and the environment are both accepted: the UI-test host and the
/// app process do not always see the same environment.
extension Notification.Name {
    static let deepLinksOpenPush = Notification.Name("dev.deeplinks.ios.open-push")
    /// Posted when APNs hands the app a device token that differs from the last
    /// one, so an open settings screen can re-register without a relaunch.
    static let deepLinksPushTokenChanged = Notification.Name("dev.deeplinks.ios.push-token-changed")
}

enum DebugE2EQRLaunch {
    static let argument = "-e2eQRPayload"
    static let environmentKey = "E2E_QR_PAYLOAD"
    static var isRequested: Bool {
        #if DEBUG
            payloadPath() != nil
        #else
            false
        #endif
    }

    #if DEBUG
        static func payloadText() -> String? {
            guard let path = payloadPath() else { return nil }
            return try? String(contentsOfFile: path, encoding: .utf8)
        }

        private static func payloadPath() -> String? {
            let arguments = ProcessInfo.processInfo.arguments
            if let index = arguments.firstIndex(of: argument), index + 1 < arguments.count {
                let path = arguments[index + 1]
                if !path.isEmpty { return path }
            }
            if let path = ProcessInfo.processInfo.environment[environmentKey], !path.isEmpty {
                return path
            }
            return nil
        }
    #endif
}

/// DEBUG-only seed for the `simctl push` NSE check (`scripts/ios-nse-simctl-push.sh`).
///
/// A real push cannot decrypt until the shared Keychain holds the test content key
/// and the matching `kid` -> `deviceId` binding. This writes both so the script can
/// exercise the NSE end to end on a simulator without an Apple account.
///
/// It never runs in Release: the whole type is compiled out, and the values are
/// throwaway test constants — never a user key.
enum DebugPushSimSeed {
    static let argument = "-CetusPushSimSeed"
    static let key = "4242424242424242424242424242424242424242424242424242424242424242"
    static let kid = "sim-kid-0001"
    static let deviceID = "sim-nse-device"

    /// Applies the seed when the launch argument is present. Returns whether it ran.
    @discardableResult
    static func applyIfRequested() -> Bool {
        #if DEBUG
            guard ProcessInfo.processInfo.arguments.contains(argument) else { return false }
            guard let bytes = decodeHex(key), bytes.count == 32 else { return false }
            do {
                let store = PushKeyStore.live()
                try store.save(bytes)
                try store.bind(kid: kid, deviceID: deviceID)
                return true
            } catch {
                return false
            }
        #else
            return false
        #endif
    }

    #if DEBUG
        /// Minimal hex decoder so the seed needs no shared helper in the app target.
        private static func decodeHex(_ text: String) -> Data? {
            guard text.count % 2 == 0 else { return nil }
            var bytes: [UInt8] = []
            var index = text.startIndex
            while index < text.endIndex {
                let next = text.index(index, offsetBy: 2)
                guard let byte = UInt8(text[index..<next], radix: 16) else { return nil }
                bytes.append(byte)
                index = next
            }
            return Data(bytes)
        }
    #endif
}
