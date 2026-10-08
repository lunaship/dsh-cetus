import Foundation
import Testing

@testable import Cetus

/// C04：跟滚策略与锚点恢复（方案 §8）。
///
/// 纯逻辑测试，不依赖 UIKit/真机，CI 可跑。
/// 真机滚动手感与长列表性能仍需真机验证（见 DELIVERY.md 未验证项）。
@Suite struct ScrollFollowStateTests {
    // MARK: - 跟滚策略

    /// 方案验收用例：上翻到中间后连续收 100 个增量，当前位置保持。
    @Test func holdsPositionAfterUserScrollsUp() {
        var tracker = TailTracker()
        #expect(tracker.policy == .follow)
        // 用户上翻（距底部 500pt，可视 800pt → 超出 15% 阈值 120pt）
        tracker.update(distanceFromBottom: 500, visibleHeight: 800)
        #expect(tracker.userScrolledUp == true)
        #expect(tracker.policy == .hold)
        // 继续收增量：仍然保持，不回到底部
        for _ in 0..<100 {
            tracker.update(distanceFromBottom: 520, visibleHeight: 800)
        }
        #expect(tracker.policy == .hold, "上翻期间收到增量不得恢复跟随")
    }

    /// 回到尾部（在阈值内）才恢复跟随。
    @Test func resumesFollowWhenBackNearTail() {
        var tracker = TailTracker(userScrolledUp: true)
        #expect(tracker.policy == .hold)
        // 距底部 50pt，可视 800pt → 阈值 120pt 内
        tracker.update(distanceFromBottom: 50, visibleHeight: 800)
        #expect(tracker.policy == .follow)
    }

    /// 阈值按比例：小屏与大屏行为一致（用比例而非固定点数）。
    @Test func thresholdScalesWithVisibleHeight() {
        var small = TailTracker()
        var large = TailTracker()
        // 同样距底 100pt：小屏 400pt（阈值 60）→ 上翻；大屏 1200pt（阈值 180）→ 贴底
        small.update(distanceFromBottom: 100, visibleHeight: 400)
        large.update(distanceFromBottom: 100, visibleHeight: 1200)
        #expect(small.policy == .hold)
        #expect(large.policy == .follow)
    }

    /// 显式"回到最新"立即恢复跟随。
    @Test func jumpToLatestResumesFollow() {
        var tracker = TailTracker(userScrolledUp: true)
        tracker.jumpToLatest()
        #expect(tracker.policy == .follow)
    }

    /// 可视高度为 0（还没布局）时不应改状态，避免首帧误判。
    @Test func zeroVisibleHeightIsIgnored() {
        var tracker = TailTracker()
        tracker.update(distanceFromBottom: 9999, visibleHeight: 0)
        #expect(tracker.policy == .follow)
    }

    // MARK: - 锚点恢复

    /// 前插三页不跳：锚点消息从第 100pt 被推到第 900pt，需补偿 800pt。
    @Test func compensationRestoresAnchorPosition() {
        let anchor = ScrollAnchor(messageID: "m42", offsetFromTop: 100)
        let compensation = AnchorResolver.compensation(anchor: anchor, newTop: 900)
        #expect(compensation == 800)
        // 应用补偿后，锚点回到原来的 100pt
        #expect(900 - compensation == anchor.offsetFromTop)
    }

    /// 没有位移时补偿为 0（幂等）。
    @Test func noShiftMeansNoCompensation() {
        let anchor = ScrollAnchor(messageID: "m1", offsetFromTop: 250)
        #expect(AnchorResolver.compensation(anchor: anchor, newTop: 250) == 0)
    }

    /// 捕获锚点：取第一个仍可见的消息（top + height > 0）。
    @Test func capturePicksFirstVisibleMessage() {
        let visible: [(id: String, top: Double, height: Double)] = [
            ("scrolled-past", -500, 100),  // 已滚过去，不应当选
            ("first-visible", 20, 80),
            ("second", 120, 60),
        ]
        let anchor = AnchorResolver.capture(visible: visible)
        #expect(anchor?.messageID == "first-visible")
        #expect(anchor?.offsetFromTop == 20)
    }

    /// 没有可见消息时不产生锚点（返回 nil，调用方应跳过恢复）。
    @Test func captureReturnsNilWhenNothingVisible() {
        let visible: [(id: String, top: Double, height: Double)] = []
        #expect(AnchorResolver.capture(visible: visible) == nil)
    }
}
