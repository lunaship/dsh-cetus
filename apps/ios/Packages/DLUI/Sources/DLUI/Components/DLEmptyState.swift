import SwiftUI

public struct DLEmptyState: View {
    private let title: String
    private let systemImage: String
    private let message: String?
    private let isEnabled: Bool

    public init(title: String, systemImage: String, message: String? = nil, isEnabled: Bool = true) {
        self.title = title
        self.systemImage = systemImage
        self.message = message
        self.isEnabled = isEnabled
    }

    public var body: some View {
        ContentUnavailableView(
            title,
            systemImage: systemImage,
            description: message.map { Text($0) }
        )
        .disabled(!isEnabled)
    }
}
