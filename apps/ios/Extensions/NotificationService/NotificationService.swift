import Foundation
import UserNotifications

final class NotificationService: UNNotificationServiceExtension {
    private let keys = PushKeyStore.live()

    override func didReceive(
        _ request: UNNotificationRequest, withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        let content = request.content.mutableCopy() as? UNMutableNotificationContent ?? UNMutableNotificationContent()
        let info = request.content.userInfo
        let ciphertext = PushPayloadReader.ciphertext(in: info)
        let deviceID = PushPayloadReader.deviceID(in: info)
        let key = (try? keys.load()) ?? Data()
        let title = PushContent.open(ciphertext: ciphertext, key: key, deviceID: deviceID)
        content.title = title
        content.body = ""
        content.categoryIdentifier = "dlpush.open"
        content.userInfo = info
        contentHandler(content)
    }
}
