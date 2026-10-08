import Foundation

public enum AppVisibility: Equatable, Sendable {
    case active
    case inactive
    case background
}

/// 隐私遮罩（C13 要求 6：App 切换器遮罩保持；**恢复时解除**，不能变成永久白屏）。
public enum PrivacyCover {
    /// 是否需要盖住当前页面。
    ///
    /// 为什么 `.inactive` 也要盖：iOS 在场景 **resign active 的瞬间**就抓 App 切换器
    /// 快照（`.inactive` 是第一个信号，`.background` 在其后）。只在 `.background` 盖
    /// 会留下一个「快照已抓、遮罩未上」的窗口，锁屏内容就泄漏了。
    ///
    /// 代价是 `.inactive` 也出现在一些**并非「切走」**的场景（下拉控制中心、来电横幅、
    /// 系统权限弹窗、Face ID）。这些场景下盖住是安全的（用户没在看内容），但必须保证
    /// 回到 `.active` 时**一定**解除 —— 见 `shouldRelease`。
    public static func covers(_ visibility: AppVisibility, screenshots: Bool = false) -> Bool {
        screenshots ? false : visibility != .active
    }

    /// 遮罩是否应当解除。与 `covers` 严格互补，单独抽出来是为了让「恢复」这件事
    /// 有**可断言的单一入口**，避免以后有人只改一边导致遮罩卡住。
    ///
    /// 兜底：`screenshots == true`（截图/快照测试）永远不盖也不留。
    public static func shouldRelease(_ visibility: AppVisibility, screenshots: Bool = false) -> Bool {
        screenshots || visibility == .active
    }

    /// 遮罩卡住时的兜底释放条件：超过该时长仍未回到 active，就允许用户手动解除。
    ///
    /// 取值理由：正常的一次「切走再回来」（控制中心、来电横幅、权限弹窗）通常是
    /// 秒级；30 秒还没回到 active，基本可以判定 scenePhase 不会再自己恢复
    /// （真机偶发的生命周期丢失、或某种卡死）。此时白屏对用户是**死路**，
    /// 必须给出口，否则只能杀 App。
    public static let manualEscapeAfter: TimeInterval = 30

    /// 在给定时刻，遮罩是否已经「卡住」到该允许手动解除。
    public static func canEscapeManually(
        coveredSince: Date?, now: Date, manualEscapeAfter seconds: TimeInterval = manualEscapeAfter
    ) -> Bool {
        guard let coveredSince else { return false }
        return now.timeIntervalSince(coveredSince) >= seconds
    }
}
