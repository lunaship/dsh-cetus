import DLSecurity
import Foundation
import UIKit
import UserNotifications

enum APNsBuild {
    static var enabled: Bool {
        guard
            let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision")
                ?? Bundle.main.url(forResource: "Cetus", withExtension: "entitlements"),
            let data = try? Data(contentsOf: url),
            let text = String(data: data, encoding: .ascii) ?? String(data: data, encoding: .utf8)
        else { return false }
        return text.contains("aps-environment")
    }

    /// The `aps-environment` value baked into the provisioning profile.
    ///
    /// RFC 0002 §16.2.1 requires the sandbox/production switch to be handled rather
    /// than assumed: a TestFlight/App Store build receives **production** tokens, and
    /// reporting `sandbox` for those makes the gateway address the wrong APNs host.
    static var environment: PushEnvironment {
        guard
            let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
            let data = try? Data(contentsOf: url),
            let value = apsEnvironment(inProfile: data)
        else {
            // No profile (a free-signed or simulator build): sandbox is the only
            // host APNs serves there.
            return .sandbox
        }
        return value == "production" ? .production : .sandbox
    }

    /// Reads `Entitlements.aps-environment` from the CMS-wrapped plist inside a
    /// provisioning profile, parsing the plist instead of matching strings.
    static func apsEnvironment(inProfile data: Data) -> String? {
        guard let start = data.range(of: Data("<?xml".utf8)),
            let end = data.range(of: Data("</plist>".utf8), in: start.lowerBound..<data.endIndex),
            let plist = try? PropertyListSerialization.propertyList(
                from: data[start.lowerBound..<end.upperBound], format: nil) as? [String: Any],
            let entitlements = plist["Entitlements"] as? [String: Any]
        else { return nil }
        return entitlements["aps-environment"] as? String
    }
}

final class PushTokenBridge: NSObject, UIApplicationDelegate, Sendable {
    static let shared = PushTokenBridge()
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Data, Error>?
    /// The last token seen by the system, so a *change* can be told apart from a
    /// repeat delivery. RFC 0002 §16.2.1 requires token updates to be handled.
    private var lastToken: Data?

    /// Latest token the system handed us, if any.
    var currentToken: Data? {
        lock.lock()
        defer { lock.unlock() }
        return lastToken
    }

    func register() async throws -> PushDeviceToken {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        if settings.authorizationStatus == .notDetermined {
            let granted = try await UNUserNotificationCenter.current().requestAuthorization(options: [
                .alert, .sound, .badge,
            ])
            guard granted else { throw PushTokenError.denied }
        } else if settings.authorizationStatus == .denied {
            throw PushTokenError.denied
        }
        let data = try await requestToken()
        return PushDeviceToken(bytes: data, environment: APNsBuild.environment)
    }

    /// Records the token. Returns `true` when it differs from the previous one,
    /// which is the signal to re-register with the plugin.
    @discardableResult
    func didRegister(deviceToken: Data) -> Bool {
        lock.lock()
        let changed = lastToken != deviceToken
        lastToken = deviceToken
        lock.unlock()
        resume(.success(deviceToken))
        return changed
    }

    func didFail(_ error: Error) {
        resume(.failure(error))
    }

    private func requestToken() async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            lock.lock()
            self.continuation = continuation
            lock.unlock()
            Task { @MainActor in
                UIApplication.shared.registerForRemoteNotifications()
            }
        }
    }

    private func resume(_ result: Result<Data, Error>) {
        lock.lock()
        let continuation = self.continuation
        self.continuation = nil
        lock.unlock()
        continuation?.resume(with: result)
    }
}

enum PushTokenError: Error {
    case denied
    case unavailable
}

/// 系统通知授权状态的只读查询（C10 要求 8）。
///
/// 设置页必须把**三个维度**分开显示，因为它们各自独立、失败方式也不同：
/// 1. **系统授权**（本类型）：用户是否允许这台手机弹通知。App 内的开关开了也拿不到它。
/// 2. **App 偏好**：用户想不想收（`settings.notify*`）。
/// 3. **网关可用性**：电脑那侧是否有推送能力（`pushVersion`）。
///
/// 把三者塞进一个布尔量，用户就会看到「开关是开的但收不到通知」而无法定位。
enum PushSystemAuthorization {
    /// 供 UI 显示的归一化结果。刻意不含 `.provisional` 等细节：
    /// 对用户而言「会弹 / 不会弹 / 还没问过」才是可行动的区分。
    enum Status: Equatable, Sendable {
        /// 还没问过——打开开关时系统会弹权限框。
        case notDetermined
        /// 已授权。
        case authorized
        /// 用户拒绝了。**只能去系统设置改**，App 内改不了。
        case denied
    }

    static func current() async -> Status {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral: return .authorized
        case .denied: return .denied
        case .notDetermined: return .notDetermined
        @unknown default: return .notDetermined
        }
    }
}
