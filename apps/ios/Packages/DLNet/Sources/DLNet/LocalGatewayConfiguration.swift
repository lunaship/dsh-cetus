import Foundation
import Network

public struct LocalGatewayConfiguration: Sendable {
    public var port: UInt16
    public var username: String
    public var password: String

    public init(port: UInt16, username: String, password: String) {
        self.port = port
        self.username = username
        self.password = password
    }

    /// 只给非回环目标使用。URLSession 对 `127.0.0.0/8` 会绕过代理，
    /// 因此回环插件地址不能靠这层完成 TLS 前鉴权。
    public func apply(to configuration: URLSessionConfiguration) {
        var proxy = ProxyConfiguration(
            httpCONNECTProxy: .hostPort(
                host: .ipv4(.loopback),
                port: NWEndpoint.Port(rawValue: port) ?? 0
            )
        )
        proxy.allowFailover = false
        proxy.applyCredential(username: username, password: password)
        configuration.proxyConfigurations = [proxy]
    }
}
