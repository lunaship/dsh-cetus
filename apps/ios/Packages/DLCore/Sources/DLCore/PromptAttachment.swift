import Foundation

/// `POST /dsh-link/mobile/sessions/:id/prompt` 的 `images[]`。
/// 插件只收 `mediaType` + base64 `data`（最多 4 张，单张 base64 不超过 4MB）。
/// 合同没有独立的文件字段，也没有裁剪参数。
public struct PromptImage: Equatable, Sendable, Codable {
    public var mediaType: String
    public var data: String

    public init(mediaType: String, data: String) {
        self.mediaType = mediaType
        self.data = data
    }
}

public enum PromptAttachmentDecision: Equatable, Sendable {
    case image(PromptImage)
    case rejected(PromptAttachmentRejection)
}

public enum PromptAttachmentRejection: Equatable, Sendable {
    /// 不是插件允许的图片类型，或声明与文件头不一致。
    case unsupported
    /// base64 后超过插件的 4MB 上限。
    case tooLarge
    /// 安全作用域里读不到字节。
    case unreadable
    /// 已经有 4 张，插件会丢掉多出来的。
    case limit
}

/// 插件 `PROMPT_IMAGE_MEDIA_TYPES`。`image/jpg` 与 `image/jpeg` 同义，发出去用 jpeg。
public let promptImageMediaTypes: Set<String> = [
    "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif",
]

/// 插件一次最多收 4 张。
public let promptImageLimit = 4

/// 插件 `PROMPT_IMAGE_DATA_LIMIT`：base64 字符串长度，不是解码后的字节数。
public let promptImageDataLimit = 4 * 1024 * 1024

/// 图片裁剪不做。合同和 prompt 请求都没有裁剪框、偏移或输出尺寸。
public let promptImageCroppingAvailable = false

public func normalizedPromptMediaType(_ raw: String) -> String? {
    let type =
        raw.split(separator: ";", maxSplits: 1).first
        .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines).lowercased() } ?? ""
    guard promptImageMediaTypes.contains(type) else { return nil }
    return type == "image/jpg" ? "image/jpeg" : type
}

/// 文件头优先。扩展名只在文件头不是这四种图片时使用。
public func sniffedPromptMediaType(bytes: Data, declared: String?) -> String? {
    if let sniffed = sniffPromptImage(bytes) { return sniffed }
    return declared.flatMap(normalizedPromptMediaType)
}

/// 把一份已读入的文件归类成可发送的图片，或一个明确的拒绝原因。
/// 不解码、不缩放、不裁剪：整文件原样做 base64。
public func classifyPromptAttachment(
    bytes: Data, declaredMediaType: String?, existingCount: Int
) -> PromptAttachmentDecision {
    guard existingCount < promptImageLimit else { return .rejected(.limit) }
    guard !bytes.isEmpty else { return .rejected(.unsupported) }
    guard let mediaType = sniffedPromptMediaType(bytes: bytes, declared: declaredMediaType) else {
        return .rejected(.unsupported)
    }
    let encoded = base64PromptData(bytes)
    guard encoded.utf8.count <= promptImageDataLimit else { return .rejected(.tooLarge) }
    return .image(PromptImage(mediaType: mediaType, data: encoded))
}

public func base64PromptData(_ bytes: Data) -> String {
    bytes.base64EncodedString()
}

private func sniffPromptImage(_ bytes: Data) -> String? {
    let header = [UInt8](bytes.prefix(12))
    if header.starts(with: [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) { return "image/png" }
    if header.starts(with: [0xFF, 0xD8, 0xFF]) { return "image/jpeg" }
    if header.starts(with: [0x47, 0x49, 0x46, 0x38]) { return "image/gif" }
    if header.count >= 12,
        header.starts(with: [0x52, 0x49, 0x46, 0x46]),
        Array(header[8..<12]) == [0x57, 0x45, 0x42, 0x50]
    {
        return "image/webp"
    }
    return nil
}

/// 没有图片时省略 `images`，保持旧 prompt 形状。
public func encodePromptRequest(text: String, mode: String, images: [PromptImage]) throws -> Data {
    try JSONEncoder().encode(PromptRequestBody(text: text, mode: mode, images: images))
}

private struct PromptRequestBody: Encodable {
    var text: String
    var mode: String
    var images: [PromptImage]

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(text, forKey: .text)
        try container.encode(mode, forKey: .mode)
        if !images.isEmpty {
            try container.encode(images, forKey: .images)
        }
    }

    private enum CodingKeys: String, CodingKey {
        case text
        case mode
        case images
    }
}
