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
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        content
            // 草稿仓库从依赖根注入一次，紧凑导航 / 宽屏详情 / 分享预填三条路
            // 都取到同一份（C02 要求 4）。测试可用 .composerDraftStore(_:) 覆盖。
            .composerDraftStore(ComposerDraftStore.live(keys: KeychainStore()))
            .overlay {
                if PrivacyCover.covers(visibility, screenshots: screenshots) {
                    Rectangle().fill(.background).ignoresSafeArea()
                }
            }
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

    private var visibility: AppVisibility {
        switch scenePhase {
        case .active: .active
        case .background: .background
        default: .inactive
        }
    }
}

final class CetusAppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
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
            let request = PushPayloadReader.openRequest(in: response.notification.request.content.userInfo)
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
        PushTokenBridge.shared.didRegister(deviceToken: deviceToken)
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
