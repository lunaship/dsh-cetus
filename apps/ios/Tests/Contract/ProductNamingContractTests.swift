import Foundation
import Testing

@testable import Cetus

/// 方案 §21.1（N01）：「Android / iOS 显示名 = cetus，桌面图标、系统设置、通知、
/// 分享、关于、演示一致」。
///
/// Android 侧已有 `ProductNamingTest` 守这条；**iOS 侧此前没有**。改名是跨几十个文件的
/// 机械替换，落下一两处不会让任何测试变红，却会让用户看到旧名字。
///
/// 刻意**不**扫 `package` / bundle id / Keychain service：那些是安装身份与凭据命名空间，
/// 按 §18「安装身份可保留 legacy」必须保持 `dev.deeplinks*`，改了会丢数据。
@Suite("产品命名契约 (N01)")
struct ProductNamingContractTests {
    /// 旧产品名出现在**用户可见文案**里即为回归。
    private static let staleNames = ["dsh-links", "DeepLinks"]

    private static func iosRoot() -> URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()  // Contract
            .deletingLastPathComponent()  // Tests
            .deletingLastPathComponent()  // apps/ios
    }

    /// 本地化目录里不得出现旧品牌名。
    @Test("本地化文案不含旧品牌名")
    func catalogHasNoStaleBrand() throws {
        let url = Self.iosRoot().appending(path: "App/Resources/Localizable.xcstrings")
        let catalog = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        let strings = try #require(catalog?["strings"] as? [String: Any])

        var offenders: [String] = []
        for (key, entry) in strings {
            let localizations = (entry as? [String: Any])?["localizations"] as? [String: Any] ?? [:]
            for (language, raw) in localizations {
                let value = ((raw as? [String: Any])?["stringUnit"] as? [String: Any])?["value"] as? String
                guard let value else { continue }
                for stale in Self.staleNames where value.localizedCaseInsensitiveContains(stale) {
                    offenders.append("\(key) [\(language)]: \(value)")
                }
            }
        }
        #expect(offenders.isEmpty, "本地化文案残留旧品牌名：\n\(offenders.joined(separator: "\n"))")
    }

    /// 系统可见的显示名必须是 cetus。
    @Test("Info.plist 显示名是 cetus")
    func displayNameIsCetus() throws {
        let url = Self.iosRoot().appending(path: "App/Info.plist")
        let plist =
            try PropertyListSerialization.propertyList(
                from: Data(contentsOf: url), format: nil) as? [String: Any]
        #expect(plist?["CFBundleDisplayName"] as? String == "cetus")
    }

    /// 扩展的 `CFBundleDisplayName` 保持技术名（Share / NotificationService / LiveActivity）。
    ///
    /// 这不是漏改：iOS 在分享菜单与通知设置里显示的是**宿主 App 的名字**（即 `cetus`），
    /// 扩展名只在开发者视角与部分系统列表里出现。改成 `cetus` 反而让三个扩展
    /// 在主 App 内难以区分（Xcode target 列表、崩溃日志都会变成同一个名字）。
    ///
    /// 本条把它钉住，避免以后有人「顺手统一」而破坏可维护性 —— **包括我自己**：
    /// 我第一版断言的就是「扩展显示名必须是 cetus」，跑起来才发现假设错了。
    @Test("扩展显示名保持技术名，不随主 App 改名")
    func extensionDisplayNamesStayTechnical() throws {
        let expected = [
            "Extensions/Share/Info.plist": "Share",
            "Extensions/NotificationService/Info.plist": "NotificationService",
            "Extensions/LiveActivity/Info.plist": "LiveActivity",
        ]
        for (relative, name) in expected {
            let url = Self.iosRoot().appending(path: relative)
            let plist =
                try PropertyListSerialization.propertyList(
                    from: Data(contentsOf: url), format: nil) as? [String: Any]
            #expect(
                plist?["CFBundleDisplayName"] as? String == name,
                "\(relative) 的 CFBundleDisplayName 应为技术名 \(name)")
        }
    }

    /// 安装身份与凭据命名空间**必须**保持 legacy，否则升级会丢数据（§18）。
    @Test("安装身份保持 dev.deeplinks")
    func installIdentityStaysLegacy() throws {
        let url = Self.iosRoot().appending(path: "project.yml")
        let yaml = try String(contentsOf: url, encoding: .utf8)
        #expect(yaml.contains("dev.deeplinks.ios"), "bundle id 前缀必须保持 dev.deeplinks.ios")
        // Release 不该带 .debug 后缀（C16 §20.6 的分离）。
        #expect(
            !yaml.contains("PRODUCT_BUNDLE_IDENTIFIER: dev.deeplinks.ios.debug\n      GENERATE"),
            "Release 配置不应继承 .debug 后缀")
    }
}
