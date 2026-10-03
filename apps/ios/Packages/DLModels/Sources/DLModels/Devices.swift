import Foundation

/// `GET /dsh-link/mobile/devices`。
public struct DevicesResponse: Codable, Equatable, Sendable {
    public var version: Int?
    public var devices: [DeviceRow]?

    public init(version: Int? = nil, devices: [DeviceRow]? = nil) {
        self.version = version
        self.devices = devices
    }
}

/// 设备行。`remote` 只表示「有没有远程能力」；`remoteHandle` 从不下发。
public struct DeviceRow: Codable, Equatable, Sendable {
    public var deviceId: String?
    public var name: String?
    public var createdAt: Int?
    public var lastSeenAt: Int?
    public var via: DeviceVia?
    public var remote: Bool?
    public var status: DeviceStatus?
    /// 仅 pending 且批准后会吊销同名旧设备时为 true（「批准即替换」）。
    public var replacing: Bool?
    public var pendingExpiresAt: Int?
    public var pairedFrom: String?

    public init(
        deviceId: String? = nil,
        name: String? = nil,
        createdAt: Int? = nil,
        lastSeenAt: Int? = nil,
        via: DeviceVia? = nil,
        remote: Bool? = nil,
        status: DeviceStatus? = nil,
        replacing: Bool? = nil,
        pendingExpiresAt: Int? = nil,
        pairedFrom: String? = nil
    ) {
        self.deviceId = deviceId
        self.name = name
        self.createdAt = createdAt
        self.lastSeenAt = lastSeenAt
        self.via = via
        self.remote = remote
        self.status = status
        self.replacing = replacing
        self.pendingExpiresAt = pendingExpiresAt
        self.pairedFrom = pairedFrom
    }
}

public enum DeviceVia: DLStringEnum {
    case lan
    case remote
    /// 旧版云端配对（DLR/1），已停用。
    case relay
    case unknown(String)

    public static func decoding(_ rawValue: String) -> Self {
        switch rawValue {
        case "lan": .lan
        case "remote": .remote
        case "relay": .relay
        default: .unknown(rawValue)
        }
    }

    public var encodedValue: String {
        switch self {
        case .lan: "lan"
        case .remote: "remote"
        case .relay: "relay"
        case .unknown(let raw): raw
        }
    }
}

public enum DeviceStatus: DLStringEnum {
    case pending
    case active
    case unknown(String)

    public static func decoding(_ rawValue: String) -> Self {
        switch rawValue {
        case "pending": .pending
        case "active": .active
        default: .unknown(rawValue)
        }
    }

    public var encodedValue: String {
        switch self {
        case .pending: "pending"
        case .active: "active"
        case .unknown(let raw): raw
        }
    }
}

/// `POST /dsh-link/mobile/revoke`：只能吊销调用方自己的设备；指向其他设备返回 403。
public struct RevokeResponse: Codable, Equatable, Sendable {
    public var ok: Bool?
    public var removed: Int?
    public var deviceId: String?

    public init(ok: Bool? = nil, removed: Int? = nil, deviceId: String? = nil) {
        self.ok = ok
        self.removed = removed
        self.deviceId = deviceId
    }
}
