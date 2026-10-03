import Foundation

/// `GET /dsh-link/mobile/previews`（设备 token）：未过期的已批准本机预览，默认 2 小时过期。
/// 手机不能批准端口；批准只在电脑回环面板完成。
public struct PreviewsResponse: Codable, Equatable, Sendable {
    public var previews: [PreviewInfo]?

    public init(previews: [PreviewInfo]? = nil) {
        self.previews = previews
    }
}

public struct PreviewInfo: Codable, Equatable, Sendable {
    /// 随机 id，不是端口号。
    public var previewId: String?
    public var label: String?
    public var port: Int?
    public var expiresAt: Int?

    public init(previewId: String? = nil, label: String? = nil, port: Int? = nil, expiresAt: Int? = nil) {
        self.previewId = previewId
        self.label = label
        self.port = port
        self.expiresAt = expiresAt
    }
}

/// `GET /dsh-link/mobile/preview-detections`：工具输出里看到的本机端口。
/// 只含端口与会话 id，没有工具输出；已批准的端口不在这里；不能借此批准端口。
public struct PreviewDetectionsResponse: Codable, Equatable, Sendable {
    public var detections: [PreviewDetection]?

    public init(detections: [PreviewDetection]? = nil) {
        self.detections = detections
    }
}

public struct PreviewDetection: Codable, Equatable, Sendable {
    public var port: Int?
    public var sessionId: String?

    public init(port: Int? = nil, sessionId: String? = nil) {
        self.port = port
        self.sessionId = sessionId
    }
}
