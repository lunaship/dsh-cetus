import Foundation

/// `POST /dsh-link/pair` 成功响应（含 pending 与替换流程）。
/// pending 为真时进等待页轮询 `GET /dsh-link/mobile/sessions`：200 = 批准，403 = 继续，401 = 拒绝或超时。
public struct PairResponse: Codable, Equatable, Sendable {
    public var ok: Bool?
    public var token: String?
    public var deviceId: String?
    public var name: String?
    public var urls: [String]?
    public var pending: Bool?
    public var pendingExpiresAt: Int?
    /// 替换立即生效（无确认闸门）时返回被吊销的旧设备 id。
    public var replacedDeviceIds: [String]?
    /// 新设备进 pending 且批准后将吊销同名旧设备。
    public var replacing: Bool?
    /// 远程已启用时附带该设备的远程凭据（RFC 0001 §6.3）；pending 设备也有。
    public var remote: DeviceRemoteInfo?

    public init(
        ok: Bool? = nil,
        token: String? = nil,
        deviceId: String? = nil,
        name: String? = nil,
        urls: [String]? = nil,
        pending: Bool? = nil,
        pendingExpiresAt: Int? = nil,
        replacedDeviceIds: [String]? = nil,
        replacing: Bool? = nil,
        remote: DeviceRemoteInfo? = nil
    ) {
        self.ok = ok
        self.token = token
        self.deviceId = deviceId
        self.name = name
        self.urls = urls
        self.pending = pending
        self.pendingExpiresAt = pendingExpiresAt
        self.replacedDeviceIds = replacedDeviceIds
        self.replacing = replacing
        self.remote = remote
    }
}

/// `POST /dsh-link/pair` 的 409 结构化冲突：App 据 `code == "SAME_NAME"` 弹
/// 「替换它 / 换个名字」；409 不消费配对码，替换时用同一张码带 `replace: true` 重发。
public struct PairConflictResponse: Codable, Equatable, Sendable {
    public var error: String?
    public var code: String?
    public var existing: ExistingDevice?

    public init(error: String? = nil, code: String? = nil, existing: ExistingDevice? = nil) {
        self.error = error
        self.code = code
        self.existing = existing
    }
}

public struct ExistingDevice: Codable, Equatable, Sendable {
    public var deviceId: String?
    public var name: String?
    public var status: String?

    public init(deviceId: String? = nil, name: String? = nil, status: String? = nil) {
        self.deviceId = deviceId
        self.name = name
        self.status = status
    }
}

/// 二维码 / `GET /dsh-link/pair-info` 载荷。`issuedAt` / `expiresAt` 为主机时钟 Unix 毫秒：
/// App 扫码后先比本机时间，超过 `expiresAt` 直接提示刷新二维码，不提交注定 401 的码。
/// 旧插件不下发这两个字段，缺失时回退为直接尝试配对。
public struct PairInfoPayload: Codable, Equatable, Sendable {
    public var v: Int?
    public var type: String?
    public var deviceId: String?
    public var name: String?
    public var port: Int?
    public var urls: [String]?
    public var infos: [PairUrlInfo]?
    public var pairingCode: String?
    public var certFingerprint: String?
    public var requireConfirm: Bool?
    public var exposure: ExposureInfo?
    public var issuedAt: Int?
    public var expiresAt: Int?
    /// 远程已就绪时的中继首配信息；`bootstrapSeed` 只编进二维码图片，pair-info JSON 不带它。
    public var remote: QrRemoteInfo?

    public init(
        v: Int? = nil,
        type: String? = nil,
        deviceId: String? = nil,
        name: String? = nil,
        port: Int? = nil,
        urls: [String]? = nil,
        infos: [PairUrlInfo]? = nil,
        pairingCode: String? = nil,
        certFingerprint: String? = nil,
        requireConfirm: Bool? = nil,
        exposure: ExposureInfo? = nil,
        issuedAt: Int? = nil,
        expiresAt: Int? = nil,
        remote: QrRemoteInfo? = nil
    ) {
        self.v = v
        self.type = type
        self.deviceId = deviceId
        self.name = name
        self.port = port
        self.urls = urls
        self.infos = infos
        self.pairingCode = pairingCode
        self.certFingerprint = certFingerprint
        self.requireConfirm = requireConfirm
        self.exposure = exposure
        self.issuedAt = issuedAt
        self.expiresAt = expiresAt
        self.remote = remote
    }
}

public struct PairUrlInfo: Codable, Equatable, Sendable {
    public var url: String?
    public var label: String?
    public var category: URLCategory?
    public var isRecommended: Bool?

    public init(
        url: String? = nil,
        label: String? = nil,
        category: URLCategory? = nil,
        isRecommended: Bool? = nil
    ) {
        self.url = url
        self.label = label
        self.category = category
        self.isRecommended = isRecommended
    }
}

public enum URLCategory: DLStringEnum {
    case loopback
    case tailnet
    case privateNetwork
    case other
    case unknown(String)

    public static func decoding(_ rawValue: String) -> Self {
        switch rawValue {
        case "loopback": .loopback
        case "tailnet": .tailnet
        case "private": .privateNetwork
        case "other": .other
        default: .unknown(rawValue)
        }
    }

    public var encodedValue: String {
        switch self {
        case .loopback: "loopback"
        case .tailnet: "tailnet"
        case .privateNetwork: "private"
        case .other: "other"
        case .unknown(let raw): raw
        }
    }
}

/// 监听暴露评估（面板与二维码附带）。App 据此提示「勿把 18640 暴露到公网」。
public struct ExposureInfo: Codable, Equatable, Sendable {
    public var listen: ListenAddress?
    public var networks: [NetworkInfo]?
    public var level: ExposureLevel?
    public var warning: String?
    public var hint: String?

    public init(
        listen: ListenAddress? = nil,
        networks: [NetworkInfo]? = nil,
        level: ExposureLevel? = nil,
        warning: String? = nil,
        hint: String? = nil
    ) {
        self.listen = listen
        self.networks = networks
        self.level = level
        self.warning = warning
        self.hint = hint
    }
}

public struct ListenAddress: Codable, Equatable, Sendable {
    public var address: String?
    public var port: Int?

    public init(address: String? = nil, port: Int? = nil) {
        self.address = address
        self.port = port
    }
}

public struct NetworkInfo: Codable, Equatable, Sendable {
    public var label: String?
    public var category: URLCategory?
    public var url: String?

    public init(label: String? = nil, category: URLCategory? = nil, url: String? = nil) {
        self.label = label
        self.category = category
        self.url = url
    }
}

public enum ExposureLevel: DLStringEnum {
    /// 只发现私有 / tailnet 网卡。
    case lan
    /// 发现公网地址或配置了 extraUrls。
    case untrusted
    case unknown(String)

    public static func decoding(_ rawValue: String) -> Self {
        switch rawValue {
        case "lan": .lan
        case "untrusted": .untrusted
        default: .unknown(rawValue)
        }
    }

    public var encodedValue: String {
        switch self {
        case .lan: "lan"
        case .untrusted: "untrusted"
        case .unknown(let raw): raw
        }
    }
}

/// 二维码里的 `remote`（RFC 0001 §5.2）：`{ e, r, s, p? }`。
public struct QrRemoteInfo: Codable, Equatable, Sendable {
    /// 中继地址。
    public var endpoint: String?
    /// routeId。
    public var routeId: String?
    /// 一次性 bootstrap 种子。
    public var bootstrapSeed: String?
    /// 可选外层证书指纹。
    public var outerCertificatePin: String?

    public init(
        endpoint: String? = nil,
        routeId: String? = nil,
        bootstrapSeed: String? = nil,
        outerCertificatePin: String? = nil
    ) {
        self.endpoint = endpoint
        self.routeId = routeId
        self.bootstrapSeed = bootstrapSeed
        self.outerCertificatePin = outerCertificatePin
    }

    private enum CodingKeys: String, CodingKey {
        case endpoint = "e"
        case routeId = "r"
        case bootstrapSeed = "s"
        case outerCertificatePin = "p"
    }
}

/// 配对响应与 bootstrap 里的 `remote`（RFC 0001 §6.3）：`{ e, r, h, k, p? }`。
public struct DeviceRemoteInfo: Codable, Equatable, Sendable {
    /// 中继地址。
    public var endpoint: String?
    /// routeId。
    public var routeId: String?
    /// 设备 handle。
    public var deviceHandle: String?
    /// 会合密钥。
    public var relayKey: String?
    /// 可选外层证书指纹。
    public var outerCertificatePin: String?

    public init(
        endpoint: String? = nil,
        routeId: String? = nil,
        deviceHandle: String? = nil,
        relayKey: String? = nil,
        outerCertificatePin: String? = nil
    ) {
        self.endpoint = endpoint
        self.routeId = routeId
        self.deviceHandle = deviceHandle
        self.relayKey = relayKey
        self.outerCertificatePin = outerCertificatePin
    }

    private enum CodingKeys: String, CodingKey {
        case endpoint = "e"
        case routeId = "r"
        case deviceHandle = "h"
        case relayKey = "k"
        case outerCertificatePin = "p"
    }
}
