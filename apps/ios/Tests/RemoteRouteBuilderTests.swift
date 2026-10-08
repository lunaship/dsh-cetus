import DLModels
import DLNet
import DLRemote
import DLSecurity
import Foundation
import Testing

@testable import Cetus

/// 凭据 → DLP/1 会合参数的合同测试（RFC 0001 §5.1、§5.2、§5.4.3、§7.4）。
///
/// 这一层是「已落盘的字符串凭据」到「隧道要的原始字节」之间唯一的转换点，
/// 因此每条长度/语义约束都单独断言：任何一条错了都是「连不上」或更糟的「连错主机」。
@Suite struct RemoteRouteBuilderTests {
    /// 合法的 16 B / 32 B base64url 素材。
    private static func b64(_ bytes: Int, seed: UInt8 = 1) -> String {
        var data = Data()
        for index in 0..<bytes { data.append(seed &+ UInt8(index % 251)) }
        return DlpCrypto.base64URL(data)
    }

    private static func info(
        endpoint: String? = "wss://relay.example/ws",
        routeId: String? = nil,
        deviceHandle: String? = nil,
        relayKey: String? = nil,
        outerPin: String? = nil
    ) -> DeviceRemoteInfo {
        DeviceRemoteInfo(
            endpoint: endpoint,
            routeId: routeId ?? b64(16, seed: 7),
            deviceHandle: deviceHandle ?? b64(16, seed: 19),
            relayKey: relayKey ?? b64(32, seed: 31),
            outerCertificatePin: outerPin)
    }

    // MARK: - 正常路径

    @Test func deviceRouteDecodesEveryFieldWithExactLength() throws {
        let routeId = Self.b64(16, seed: 7)
        let handle = Self.b64(16, seed: 19)
        let key = Self.b64(32, seed: 31)
        let route = try RemoteRouteBuilder.deviceRoute(
            info: Self.info(routeId: routeId, deviceHandle: handle, relayKey: key))

        let unwrapped = try #require(route)
        #expect(unwrapped.endpoint == "wss://relay.example/ws")
        #expect(unwrapped.routeId.count == DlpWire.routeBytes)
        #expect(unwrapped.keyId.count == DlpWire.keyBytes)
        #expect(unwrapped.key.count == 32)
        #expect(unwrapped.kind == .device)
        // 逐字节回环：解码结果必须与原始素材一致，不能只是长度对。
        #expect(unwrapped.routeId == (try DlpCrypto.base64URLDecode(routeId)))
        #expect(unwrapped.keyId == (try DlpCrypto.base64URLDecode(handle)))
        #expect(unwrapped.key == (try DlpCrypto.base64URLDecode(key)))
        #expect(unwrapped.outerPin.isEmpty)
    }

    // MARK: - 缺键 / 长度不符一律不可远程（§7.4：不得据此删凭据）

    @Test func missingOrMalformedCredentialsYieldNoRoute() throws {
        // 完全没配远程
        #expect(try RemoteRouteBuilder.deviceRoute(info: nil) == nil)
        // 缺各字段
        #expect(try RemoteRouteBuilder.deviceRoute(info: DeviceRemoteInfo()) == nil)
        #expect(try RemoteRouteBuilder.deviceRoute(info: Self.info(endpoint: nil)) == nil)
        #expect(try RemoteRouteBuilder.deviceRoute(info: Self.info(endpoint: "")) == nil)
        #expect(try RemoteRouteBuilder.deviceRoute(info: Self.info(routeId: "")) == nil)
        #expect(try RemoteRouteBuilder.deviceRoute(info: Self.info(deviceHandle: "")) == nil)
        #expect(try RemoteRouteBuilder.deviceRoute(info: Self.info(relayKey: "")) == nil)
        // 长度不符（15 B / 31 B / 17 B）—— RFC §5.1 要求精确长度
        #expect(try RemoteRouteBuilder.deviceRoute(info: Self.info(routeId: Self.b64(15))) == nil)
        #expect(try RemoteRouteBuilder.deviceRoute(info: Self.info(routeId: Self.b64(17))) == nil)
        #expect(try RemoteRouteBuilder.deviceRoute(info: Self.info(deviceHandle: Self.b64(15))) == nil)
        #expect(try RemoteRouteBuilder.deviceRoute(info: Self.info(relayKey: Self.b64(31))) == nil)
        #expect(try RemoteRouteBuilder.deviceRoute(info: Self.info(relayKey: Self.b64(33))) == nil)
    }

    @Test func nonBase64URLAndPaddedInputAreRejected() throws {
        // 标准 base64 字母表（含 + /）与带填充都不合法。
        #expect(try RemoteRouteBuilder.deviceRoute(info: Self.info(routeId: "!!!!!!!!!!!!!!!!")) == nil)
        #expect(
            try RemoteRouteBuilder.deviceRoute(info: Self.info(routeId: Self.b64(16) + "==")) == nil,
            "带填充必须被拒")
        #expect(try RemoteRouteBuilder.deviceRoute(info: Self.info(relayKey: "not base64 at all $$$")) == nil)
    }

    // MARK: - endpoint 规范化（§5.1）

    @Test func endpointMustBeAbsoluteWithHostAndNoCredentials() throws {
        // 生产只接受 wss://（Debug 允许 ws://，由编译条件放行，见下一条）。
        #expect(try RemoteRouteBuilder.deviceRoute(info: Self.info(endpoint: "http://relay.example/ws")) == nil)
        #expect(try RemoteRouteBuilder.deviceRoute(info: Self.info(endpoint: "relay.example.com")) == nil)
        #expect(try RemoteRouteBuilder.deviceRoute(info: Self.info(endpoint: "   ")) == nil)
        // 带账号 / 锚点一律拒绝（与插件侧 normalizeEndpoint 一致）。
        #expect(try RemoteRouteBuilder.deviceRoute(info: Self.info(endpoint: "wss://u:p@relay.example/ws")) == nil)
        #expect(try RemoteRouteBuilder.deviceRoute(info: Self.info(endpoint: "wss://relay.example/ws#frag")) == nil)
        // 合法：自定义路径原样保留。
        let ok = try #require(
            try RemoteRouteBuilder.deviceRoute(info: Self.info(endpoint: "wss://relay.example:8443/pipe")))
        #expect(ok.endpoint == "wss://relay.example:8443/pipe")
    }

    @Test func debugBuildAllowsInsecureWebSocketForTests() throws {
        #if DEBUG
            let route = try #require(
                try RemoteRouteBuilder.deviceRoute(info: Self.info(endpoint: "ws://127.0.0.1:9/ws")))
            #expect(route.endpoint == "ws://127.0.0.1:9/ws")
        #else
            #expect(try RemoteRouteBuilder.deviceRoute(info: Self.info(endpoint: "ws://127.0.0.1:9/ws")) == nil)
        #endif
    }

    // MARK: - outerPin：非法必须 fail-closed（§4.3，不能降级到系统 CA）

    @Test func invalidOuterPinFailsClosedInsteadOfDowngradingToSystemCA() throws {
        // 格式非法的钉扎：宁可拒绝，也不能当作「没有钉扎」而退回系统 CA。
        for bad in ["abcd", String(repeating: "zz", count: 32), String(repeating: "a", count: 63)] {
            #expect(throws: RouteBuildError.invalidOuterPin) {
                _ = try RemoteRouteBuilder.deviceRoute(info: Self.info(outerPin: bad))
            }
        }
        // 注意：'z' 不是 hex，长度 64 也不行。
        #expect(throws: RouteBuildError.invalidOuterPin) {
            _ = try RemoteRouteBuilder.deviceRoute(info: Self.info(outerPin: String(repeating: "z", count: 64)))
        }
    }

    @Test func validOuterPinIsNormalizedToLowercaseHex() throws {
        let pin = String(repeating: "AB", count: 32)
        let route = try #require(try RemoteRouteBuilder.deviceRoute(info: Self.info(outerPin: pin)))
        #expect(route.outerPin == String(repeating: "ab", count: 32))

        // 允许用户粘贴带冒号的写法（插件侧 normalizeOuterPin 同样接受）。
        let withColons = Array(repeating: "AB", count: 32).joined(separator: ":")
        let route2 = try #require(try RemoteRouteBuilder.deviceRoute(info: Self.info(outerPin: withColons)))
        #expect(route2.outerPin == String(repeating: "ab", count: 32))
    }

    @Test func emptyOuterPinMeansSystemCA() throws {
        let route = try #require(try RemoteRouteBuilder.deviceRoute(info: Self.info(outerPin: "")))
        #expect(route.outerPin.isEmpty, "空钉扎 = 按系统 CA 校验（§5.1）")
        let route2 = try #require(try RemoteRouteBuilder.deviceRoute(info: Self.info(outerPin: "   ")))
        #expect(route2.outerPin.isEmpty)
    }

    // MARK: - bootstrap 档（§7.6 第 3 步）

    @Test func bootstrapRouteDerivesIdAndKeyFromSeedNotFromStore() throws {
        let seed = Data((0..<16).map { UInt8($0) &+ 1 })
        let routeId = Self.b64(16, seed: 3)
        let route = try #require(
            try RemoteRouteBuilder.bootstrapRoute(
                endpoint: "wss://relay.example/ws", routeId: routeId, bootstrapSeed: seed, outerPin: nil))

        #expect(route.kind == .bootstrap)
        #expect(route.keyId.count == 16)
        #expect(route.key.count == 32)
        // 必须与 RFC 派生一致（HKDF-SHA256，salt=routeId，info 见 §5.2）。
        let routeBytes = try DlpCrypto.base64URLDecode(routeId)
        let material = try DlpCrypto.bootstrapKeys(seed: seed, route: routeBytes)
        #expect(route.keyId == material.bootstrapId)
        #expect(route.key == material.bootstrapKey)
    }

    @Test func bootstrapRouteRejectsWrongSeedLengthOrRouteId() throws {
        let seed = Data(repeating: 1, count: 16)
        // seed 必须 16 B
        #expect(
            try RemoteRouteBuilder.bootstrapRoute(
                endpoint: "wss://relay.example/ws", routeId: Self.b64(16), bootstrapSeed: Data(repeating: 1, count: 15),
                outerPin: nil) == nil)
        // routeId 必须 16 B
        #expect(
            try RemoteRouteBuilder.bootstrapRoute(
                endpoint: "wss://relay.example/ws", routeId: Self.b64(17), bootstrapSeed: seed, outerPin: nil) == nil)
        #expect(
            try RemoteRouteBuilder.bootstrapRoute(
                endpoint: nil, routeId: Self.b64(16), bootstrapSeed: seed, outerPin: nil) == nil)
    }

    @Test func bootstrapRouteAlsoFailsClosedOnBadOuterPin() throws {
        let seed = Data(repeating: 1, count: 16)
        #expect(throws: RouteBuildError.invalidOuterPin) {
            _ = try RemoteRouteBuilder.bootstrapRoute(
                endpoint: "wss://relay.example/ws", routeId: Self.b64(16), bootstrapSeed: seed, outerPin: "nope")
        }
    }

    // MARK: - 远程能力判定（只判凭据齐全，不代表中继在线）

    @Test func remoteCapabilityReflectsCredentialCompletenessOnly() {
        let full = PairedHost(
            hostId: "h", name: "n", primaryUrl: "https://192.0.2.10:18640",
            certFingerprint: String(repeating: "ab", count: 32), remote: Self.info(), pairedAt: 0)
        #expect(RemoteRouteBuilder.hasRemoteCapability(full))

        // 缺 remote 对象 / 缺字段 → 无远程能力，回退 LAN。
        let lanOnly = PairedHost(
            hostId: "h", name: "n", primaryUrl: "https://192.0.2.10:18640",
            certFingerprint: String(repeating: "ab", count: 32), remote: nil, pairedAt: 0)
        #expect(!RemoteRouteBuilder.hasRemoteCapability(lanOnly))

        let partial = PairedHost(
            hostId: "h", name: "n", primaryUrl: "https://192.0.2.10:18640",
            certFingerprint: String(repeating: "ab", count: 32),
            remote: DeviceRemoteInfo(endpoint: "wss://relay.example/ws", routeId: Self.b64(16)), pairedAt: 0)
        #expect(!RemoteRouteBuilder.hasRemoteCapability(partial))
    }

    // MARK: - clockOffsetSec 透传（§5.7 CLOCK_SKEW 重试用）

    @Test func clockOffsetIsCarriedThrough() throws {
        let route = try #require(try RemoteRouteBuilder.deviceRoute(info: Self.info(), clockOffsetSec: 42))
        #expect(route.clockOffsetSec == 42)
    }
}
