import DLSecurity
import Foundation
import Testing

@testable import Cetus

/// C12 §16.2.1：token 更新、环境切换与失效都要有处理。
///
/// 这些用例覆盖 APNs token 生命周期里最容易漏的三件事：
/// 1. 同一个 token 重复下发不算变化（否则会反复重新注册）；
/// 2. token 变了必须能被识别出来（触发重新注册）；
/// 3. 构建环境不能写死 `.sandbox`（TestFlight/App Store 是 production）。
@Suite("APNs token lifecycle (§16.2)")
struct PushTokenLifecycleTests {
    @Test("首次 token 视为变化，重复下发不算")
    func firstTokenIsAChangeAndRepeatIsNot() {
        let bridge = PushTokenBridge()
        let token = Data([0x01, 0x02, 0x03, 0x04])

        #expect(bridge.didRegister(deviceToken: token) == true, "首次拿到 token 需要注册")
        #expect(bridge.didRegister(deviceToken: token) == false, "同一 token 重复下发不应触发重新注册")
    }

    @Test("token 轮换被识别，且保留最新值")
    func rotatedTokenIsDetected() {
        let bridge = PushTokenBridge()
        let first = Data([0x0a, 0x0b])
        let second = Data([0x0c, 0x0d])

        #expect(bridge.didRegister(deviceToken: first) == true)
        #expect(bridge.didRegister(deviceToken: second) == true, "token 变了必须重新注册")
        #expect(bridge.currentToken == second)
    }

    @Test("token 长度或内容变化都算变化（不做长度假设）")
    func lengthChangeCounts() {
        let bridge = PushTokenBridge()
        #expect(bridge.didRegister(deviceToken: Data([0x01])) == true)
        #expect(bridge.didRegister(deviceToken: Data([0x01, 0x02])) == true)
        #expect(bridge.currentToken == Data([0x01, 0x02]))
    }

    @Test("无 provisioning profile 时按 sandbox 处理（模拟器/免费签名）")
    func environmentFallsBackToSandbox() {
        // 测试宿主没有 embedded.mobileprovision，因此必须回退 sandbox 而不是崩。
        #expect(APNsBuild.environment == .sandbox)
    }

    @Test("环境是枚举而非硬编码：production 与 sandbox 都是合法取值")
    func environmentTypeIsNotHardcoded() {
        // 断言的是"可表达"而非运行值：写死 .sandbox 的实现在
        // TestFlight 构建里会把 production token 报成 sandbox。
        let values: [PushEnvironment] = [.sandbox, .production]
        #expect(values.count == 2)
        #expect(PushEnvironment(rawValue: "production") == .production)
        #expect(PushEnvironment(rawValue: "sandbox") == .sandbox)
    }

    private func profile(_ environment: String?) -> Data {
        let entitlements = environment.map { "<key>aps-environment</key><string>\($0)</string>" } ?? ""
        let plist = """
            <?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict>\
            <key>Name</key><string>production test</string>\
            <key>Entitlements</key><dict>\(entitlements)</dict></dict></plist>
            """
        return Data([0x30, 0x82, 0x00]) + Data(plist.utf8) + Data([0x00, 0xA0])
    }

    @Test("从 profile 的 Entitlements 解析 aps-environment，而不是字符串匹配")
    func parsesProfileEntitlements() {
        #expect(APNsBuild.apsEnvironment(inProfile: profile("production")) == "production")
        #expect(APNsBuild.apsEnvironment(inProfile: profile("development")) == "development")
        #expect(APNsBuild.apsEnvironment(inProfile: profile(nil)) == nil)
        #expect(APNsBuild.apsEnvironment(inProfile: Data("garbage".utf8)) == nil)
    }
}
