import Foundation
import UserNotifications

// The extension compiles the PushRegistration / PushKeyStore / SecureStore
// sources directly into its own target (see apps/ios/project.yml), so there is
// no `import DLSecurity` here — adding one breaks the build.
final class NotificationService: UNNotificationServiceExtension {
    private let keys = PushKeyStore.live()

    /// Decrypts locally and never opens a connection.
    ///
    /// RFC 0002 keeps `deviceId` out of the APNs payload, so the content AAD is
    /// rebuilt from the `kid` -> `deviceId` binding written at registration. Any
    /// failure (unknown `kid`, missing key, tampered ciphertext, stale `ts`)
    /// falls back to the generic body inside `PushContent.open`; the extension
    /// must never surface unverifiable plaintext.
    override func didReceive(
        _ request: UNNotificationRequest, withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        let content = request.content.mutableCopy() as? UNMutableNotificationContent ?? UNMutableNotificationContent()
        let info = request.content.userInfo
        let ciphertext = PushPayloadReader.ciphertext(in: info)
        let deviceID = PushPayloadReader.deviceID(in: info, bindings: keys)
        let key = (try? keys.load()) ?? Data()
        let title = PushContent.open(ciphertext: ciphertext, key: key, deviceID: deviceID ?? "")
        content.title = title
        // The body stays empty on purpose: tool names, file names, and commands
        // must never reach the lock screen.
        content.body = ""
        content.categoryIdentifier = PushNotificationCategory.identifier
        content.userInfo = info
        contentHandler(content)
    }
}
