import Foundation

/// 消息流的跟滚策略（方案 §8 / C04）。
///
/// 旧实现：`ConversationPage` 生产路径把 `pinsToTail` 硬编码为 true，
/// `MessageStreamView` 在内容变化时无条件 `scrollToItem(... .bottom)`。
/// 结果：用户上翻读历史时会被**强制拉回底部**。
///
/// 现在区分三种行为：
/// - 首次进入 → 定位最新
/// - 接近尾部 → 跟随
/// - 向上阅读 → **保持锚点**，不跟随
///
/// 这里的判定是纯逻辑（不碰 UIKit），所以能在 Linux/CI 上跑真测试。
public enum FollowPolicy: Equatable, Sendable {
    /// 跟随尾部（有增量就贴底）。
    case follow
    /// 保持当前阅读位置：不自动滚动。
    case hold
}

/// 判断"离底部多近算贴底"的阈值与状态机。
public struct TailTracker: Equatable, Sendable {
    /// 距离底部小于这个比例（相对可视高度）就算"接近尾部"。
    ///
    /// 用比例而不是绝对点数：横竖屏、不同机型的可视高度差很多，
    /// 固定点数会在小屏上过敏感、大屏上不生效。
    public static let tailThresholdFraction: Double = 0.15

    /// 用户是否明确上翻过（一旦上翻，就进入 hold，直到显式回到底部）。
    public var userScrolledUp = false

    public init(userScrolledUp: Bool = false) {
        self.userScrolledUp = userScrolledUp
    }

    /// 根据滚动位置更新"用户是否在上翻"。
    ///
    /// - Parameters:
    ///   - distanceFromBottom: 当前距底部的点数（>=0）。
    ///   - visibleHeight: 可视高度（>0）。
    /// - Returns: 更新后的 tracker。
    ///
    /// 设计要点：**只用位置判断会抖**——惯性滚动经过底部时会误判为"回到底部"。
    /// 所以一旦判定为上翻，只有距底部真正进入阈值内才恢复跟随。
    public mutating func update(distanceFromBottom: Double, visibleHeight: Double) {
        guard visibleHeight > 0 else { return }
        let threshold = visibleHeight * Self.tailThresholdFraction
        if distanceFromBottom <= threshold {
            userScrolledUp = false
        } else {
            userScrolledUp = true
        }
    }

    /// 显式"回到最新"（用户点了入口，或自己发完消息）。
    public mutating func jumpToLatest() {
        userScrolledUp = false
    }

    /// 用户主动上翻。
    public mutating func markScrolledUp() {
        userScrolledUp = true
    }

    public var policy: FollowPolicy {
        userScrolledUp ? .hold : .follow
    }
}

/// 历史分页时的锚点恢复（方案 §8 要求 4）。
///
/// 前插旧消息会让所有内容下移，如果不记录锚点，用户正在读的那条会跳走。
public struct ScrollAnchor: Equatable, Sendable {
    /// 锚点消息 ID。
    public var messageID: String
    /// 该消息顶部相对可视区顶部的偏移（点数），用于精确恢复。
    public var offsetFromTop: Double

    public init(messageID: String, offsetFromTop: Double) {
        self.messageID = messageID
        self.offsetFromTop = offsetFromTop
    }
}

public enum AnchorResolver {
    /// 翻页前记录：取当前首个可见消息的 ID 与它相对顶部的偏移。
    ///
    /// - Parameter visible: 有序的可见消息（ID, 顶部 y, 高度），按显示顺序。
    /// - Returns: 锚点；没有任何可见消息时返回 nil。
    public static func capture(
        visible: [(id: String, top: Double, height: Double)]
    ) -> ScrollAnchor? {
        // 取第一个"完整或部分可见"的消息：top + height > 0 表示还没滚过去。
        guard let first = visible.first(where: { $0.top + $0.height > 0 }) else { return nil }
        return ScrollAnchor(messageID: first.id, offsetFromTop: first.top)
    }

    /// 前插后恢复：算出为了让锚点回到原偏移，需要补偿的位移。
    ///
    /// - Parameters:
    ///   - anchor: 翻页前记录的锚点。
    ///   - newTop: 锚点消息在前插后的新顶部 y。
    /// - Returns: 内容应当下移的点数（正值表示内容向下推）。
    ///
    /// 例：锚点原来在 y=100，前插一页后它跑到 y=900，
    /// 那就要把内容上移 800，用户看到的画面才不变。
    public static func compensation(anchor: ScrollAnchor, newTop: Double) -> Double {
        newTop - anchor.offsetFromTop
    }
}
