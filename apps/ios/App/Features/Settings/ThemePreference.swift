import SwiftUI

/// 外观偏好（C10 要求 5）。
///
/// 存在的理由：外观必须能在**根层**应用，才能覆盖导航、sheet、警报、消息、
/// WebView 与后续所有页面。此前设置页把选择写进 `@AppStorage("settings.theme")`，
/// 但**没有任何地方读它** —— 用户选了深色，界面毫无变化，属于静默失效。
///
/// 这里把「存储键」和「字符串 → ColorScheme」的换算收在一处，供根视图与设置页共用，
/// 避免两边各写一份字符串常量而再次走散。
enum ThemePreference {
    static let storageKey = "settings.theme"

    /// 与设置页 Picker 的 tag 一致：跟随系统 / 浅色 / 深色。
    static let system = "system"
    static let light = "light"
    static let dark = "dark"

    /// 把偏好换算为 SwiftUI 的配色覆盖。
    ///
    /// `nil` = **不覆盖**，交回系统 —— 这正是「跟随系统」的正确实现；
    /// 返回 `.light`/`.dark` 才是显式覆盖。
    /// 未知取值按「跟随系统」处理，不给用户一个坏掉的界面。
    static func colorScheme(for raw: String) -> ColorScheme? {
        switch raw {
        case light: .light
        case dark: .dark
        default: nil
        }
    }
}
