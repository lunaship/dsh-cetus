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
