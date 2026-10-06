import UserNotifications

final class NotificationService: UNNotificationServiceExtension {
    override func didReceive(
        _ request: UNNotificationRequest, withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        let content = request.content.mutableCopy() as? UNMutableNotificationContent ?? UNMutableNotificationContent()
        let info = request.content.userInfo
        let ciphertext = info["ct"] as? String ?? ""
        let deviceID = info["deviceId"] as? String ?? ""
        content.title = PushContent.open(ciphertext: ciphertext, key: Data(), deviceID: deviceID)
        content.body = ""
        content.categoryIdentifier = "dlpush.open"
        contentHandler(content)
    }
}
