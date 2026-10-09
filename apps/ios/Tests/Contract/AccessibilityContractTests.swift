import DLCore
import Foundation
import Testing
import UIKit

@testable import Cetus

/// C15 §19.3 R7：辅助功能不能只在最后截图时补。
///
/// 方案点名要覆盖「降低透明度、增强对比度、粗体文字、减弱动态效果、VoiceOver」。
/// 前两项已有截图矩阵（见各 `*SnapshotTests` 的 `reduce-transparency` /
/// `increase-contrast` 变体），本套守住**代码层面的三条前提** —— 它们一旦被破坏，
/// 矩阵再多也看不出来：
///
/// 1. 字体必须用**语义化系统字**（`Font.headline` 等）。硬编码字号/字重会让
///    「粗体文字」与动态字体同时失效，而截图在标准设置下完全正常。
/// 2. 减弱动态效果必须在动画前真的被检查，否则关了开关还会动。
/// 3. 关键交互元素要有稳定的 `accessibilityIdentifier`，否则 UI 测试只能靠
///    会随文案变化的可见文本定位。
@MainActor
@Suite("辅助功能契约 (C15 §19.3 R7)")
struct AccessibilityContractTests {
    private static func source(_ relative: String) throws -> String {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()  // Contract
            .deletingLastPathComponent()  // Tests
            .deletingLastPathComponent()  // apps/ios
            .deletingLastPathComponent()  // apps
        return try String(contentsOf: root.appending(path: relative), encoding: .utf8)
    }

    /// 字号 token 必须来自语义化系统字。
    ///
    /// 反例是 `Font.system(size: 17, weight: .semibold)`：它在标准设置下看起来没问题，
    /// 但「设置 → 显示与亮度 → 粗体文字」打开后**不会**变粗，动态字体也不跟随。
    @Test("字号 token 使用语义化系统字，不用固定 size")
    func fontTokensAreSemantic() throws {
        let source = try Self.source("ios/Packages/DLUI/Sources/DLUI/Theme/DLFont.swift")
        #expect(
            !source.contains(".system(size:"),
            "DLFont 出现固定 size —— 会让粗体文字与动态字体失效")
        // 至少要有若干语义化 token，否则说明被换成了硬编码。
        for token in ["Font.title3", "Font.headline", "Font.body", "Font.footnote"] {
            #expect(source.contains(token), "缺少语义化 token \(token)")
        }
    }

    /// 网页内容渲染不得绕过动态字体。
    @Test("消息行使用 DLFont token 而不是硬编码字号")
    func messageRowUsesTokens() throws {
        let source = try Self.source("ios/App/Features/Chat/MessageRowView.swift")
        #expect(!source.contains(".system(size:"), "消息行出现固定 size")
    }

    /// 减弱动态效果必须在真正做动画的地方被检查。
    @Test("动画处检查了 reduceMotion")
    func animationsRespectReduceMotion() throws {
        let stream = try Self.source("ios/App/Features/Chat/MessageStreamView.swift")
        // 数据刷新必须按 reduceMotion 决定是否动画。
        #expect(
            stream.contains("animated: !chrome.staticSnapshot && !chrome.reduceMotion"),
            "消息流刷新没有按 reduceMotion 关闭动画")

        let row = try Self.source("ios/App/Features/Chat/MessageRowView.swift")
        #expect(row.contains("reduceMotion"), "消息行淡入没有检查 reduceMotion")
    }

    /// 关键交互元素要有稳定标识。
    ///
    /// 少了它，UI 测试只能靠可见文本定位，而文案会随语言与措辞变化 ——
    /// 测试会在改文案时莫名其妙地红，或者更糟：静默匹配到错的元素。
    @Test("关键交互元素有 accessibilityIdentifier")
    func keyElementsHaveStableIdentifiers() throws {
        let expected: [(String, String)] = [
            ("ios/App/Features/Chat/MessageStreamView.swift", "message-stream"),
            ("ios/App/Features/Chat/MessageStreamView.swift", "load-older"),
            ("ios/App/CetusApp.swift", "privacy-cover"),
        ]
        for (file, identifier) in expected {
            let source = try Self.source(file)
            #expect(
                source.contains("\"\(identifier)\""),
                "\(file) 缺少 accessibilityIdentifier \"\(identifier)\"")
        }
    }

    /// 图标按钮必须有可理解的 label —— 否则 VoiceOver 只念「按钮」。
    @Test("纯图标按钮带 accessibilityLabel")
    func iconOnlyButtonsAreLabelled() throws {
        // 这两个是真实的纯图标控件（省略号菜单、扫码页关闭）。
        let inbox = try Self.source("ios/App/Features/Home/InboxPage.swift")
        #expect(inbox.contains(".accessibilityLabel(copy.text(.more))"), "省略号菜单缺 label")

        let pairing = try Self.source("ios/App/Features/Pairing/PairingPages.swift")
        #expect(pairing.contains(".accessibilityLabel(copy.text(.close))"), "扫码关闭按钮缺 label")
    }

    /// 装饰性图标应显式对读屏隐藏，避免念出无意义的图标名。
    @Test("相机预览对读屏隐藏")
    func cameraPreviewIsHidden() throws {
        let pairing = try Self.source("ios/App/Features/Pairing/PairingPages.swift")
        #expect(
            pairing.contains("accessibilityHidden(true)"),
            "相机预览没有 accessibilityHidden —— 读屏会念出无意义内容")
    }
}
