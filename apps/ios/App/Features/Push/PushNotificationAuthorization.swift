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
}

final class PushTokenBridge: NSObject, UIApplicationDelegate, Sendable {
    static let shared = PushTokenBridge()
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Data, Error>?

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
        return PushDeviceToken(bytes: data, environment: .sandbox)
    }

    func didRegister(deviceToken: Data) {
        resume(.success(deviceToken))
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
