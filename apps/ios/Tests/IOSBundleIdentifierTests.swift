import Foundation
import Testing

/// C16 §20.6：Debug 与 Release 不能共用同一个 bundle id。
///
/// 背景：`apps/ios/project.yml` 原本把 `dev.deeplinks.ios.debug*` 硬编码在每个
/// target 的 `settings.base`，于是 Debug 与 Release 产出**同一个 id**：
/// 两者无法在同一台设备并存，且 `.debug` 后缀会随生产包一起发出去。
///
/// Android 一直用 `applicationIdSuffix = ".debug"`（`build.gradle.kts:118`）区分，
/// iOS 现在对齐同一结构：base 用维护者登记的 legacy id，仅 Debug 追加 `.debug`。
///
/// 本测试读 project.yml 文本（不依赖 Xcode 构建），钉住三件事：
/// 1. 六个 target 的 Debug id 与历史值**逐字相同**（否则 CI/UI 测试与已装调试包会失效）；
/// 2. Debug 与 Release 的 id 必须不同（这正是 C16 要的分离）；
/// 3. 三个扩展的 id 必须与主 App 同前缀（签名与共享 Keychain 依赖这个关系）。
@Suite("iOS bundle id 配置分离 (C16 §20.6)")
struct IOSBundleIdentifierTests {
    private static func projectYAML() throws -> String {
        // apps/ios/Tests -> apps/ios -> repo root -> apps/ios/project.yml
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()  // Tests
            .deletingLastPathComponent()  // apps/ios
        let url = root.appending(path: "project.yml")
        return try String(contentsOf: url, encoding: .utf8)
    }

    /// The ids the project shipped before the split. Debug must match these.
    private static let legacyDebugIDs = [
        "dev.deeplinks.ios.debug",
        "dev.deeplinks.ios.debug.notification-service",
        "dev.deeplinks.ios.debug.live-activity",
        "dev.deeplinks.ios.debug.share",
        "dev.deeplinks.ios.debug.tests",
        "dev.deeplinks.ios.debug.uitests",
    ]

    @Test("Debug 的 bundle id 与历史值逐字相同")
    func debugIdentifiersAreUnchanged() throws {
        let yaml = try Self.projectYAML()
        for id in Self.legacyDebugIDs {
            #expect(yaml.contains(id), "Debug 必须保留 \(id)（CI/UI 测试与已装调试包依赖它）")
        }
    }

    /// 这条同时守住「xcodegen 能解析 project.yml」：`configs` 块若被写错位置
    /// （设置掉进 configs 里），`xcodegen generate` 会直接失败，CI 构建就红了。
    @Test("Release 不再使用 .debug 后缀（配置已分离）")
    func releaseIdentifiersDifferFromDebug() throws {
        let yaml = try Self.projectYAML()
        // 每个 target 都要有显式的 Debug/Release 两个配置块，
        // 且 base 里不能再出现 .debug 后缀（否则 Release 会继承它）。
        //
        // 注意用 `separated(by:)` 计数要减 1：分隔串自带换行，命中 N 次会得到 N+1 段。
        // 这里要的是「裸 `.debug` id 恰好出现 1 次」——只该出现在 Cetus 的 Debug 配置里
        //（CetusTests / CetusUITests 的 id 形如 `.debug.tests`，不匹配这个精确串）。
        let bareDebugID = "PRODUCT_BUNDLE_IDENTIFIER: dev.deeplinks.ios.debug\n"
        let occurrences = yaml.components(separatedBy: bareDebugID).count - 1
        #expect(occurrences == 1, "裸 .debug id 只该出现在 Cetus 的 Debug 配置里，实际 \(occurrences) 次")

        for suffix in ["", ".notification-service", ".live-activity", ".share"] {
            let release = "PRODUCT_BUNDLE_IDENTIFIER: dev.deeplinks.ios\(suffix)\n"
            let debug = "PRODUCT_BUNDLE_IDENTIFIER: dev.deeplinks.ios.debug\(suffix)\n"
            #expect(yaml.contains(release), "Release 需要有 \(release.trimmingCharacters(in: .whitespaces))")
            #expect(yaml.contains(debug), "Debug 需要有 \(debug.trimmingCharacters(in: .whitespaces))")
            #expect(release != debug)
        }
    }

    @Test("主 App 与三个扩展同前缀（签名与共享 Keychain 依赖）")
    func extensionsShareTheAppPrefix() throws {
        let yaml = try Self.projectYAML()
        for suffix in [".notification-service", ".live-activity", ".share"] {
            #expect(
                yaml.contains("dev.deeplinks.ios.debug\(suffix)"),
                "扩展 \(suffix) 的 Debug id 必须以主 App 的 Debug id 为前缀")
        }
    }
}
