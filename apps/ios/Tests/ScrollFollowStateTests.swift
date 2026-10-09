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

// MARK: - T14：上翻中持续接收增量，不被拉回最新

/// 方案的 T14 要求「上翻中持续接收增量 → 不被拉回最新」，验收证据写的是
/// 「100 增量录像/锚点」。录像需要真机，但**策略层**可以在这里压满：
/// 一次跑 100 次增量，逐步断言策略始终是 hold、锚点始终能恢复。
///
/// 这条比"单次增量"更有价值的地方在于：真实 bug 往往是**累积漂移**
/// （每次补偿差几个点，100 次后画面跑掉），单次断言看不出来。
@Suite struct SustainedIncrementFollowTests {
    private let viewport = 800.0

    /// 模拟一次「上翻读历史 + 持续来增量」。
    /// 返回 100 次增量后是否仍保持 hold，以及锚点的最大漂移。
    private func runIncrements(count: Int, driftPerStep: Double) throws -> (
        heldEveryStep: Bool, maxDrift: Double
    ) {
        var tracker = TailTracker()
        // 用户上翻：距底部一屏之外。
        tracker.update(distanceFromBottom: 2000, visibleHeight: viewport)
        #expect(tracker.policy == .hold, "上翻后应进入 hold")

        let anchor = try #require(
            AnchorResolver.capture(visible: [
                (id: "m-anchor", top: 120, height: 80),
                (id: "m-next", top: 200, height: 80),
            ]))
        var maxDrift = 0.0
        var heldEveryStep = true

        for step in 1...count {
            // 每来一条增量，内容高度增加，锚点的绝对位置被推下去。
            let addedHeight = driftPerStep * Double(step)
            let newTop = 120 + addedHeight
            let delta = AnchorResolver.compensation(anchor: anchor, newTop: newTop)
            // 补偿后锚点应当回到原来的偏移（120）。
            let restoredTop = newTop - delta
            maxDrift = max(maxDrift, abs(restoredTop - 120))
            // 位置没变 → 仍远离底部 → 策略必须继续是 hold。
            tracker.update(distanceFromBottom: 2000 + addedHeight, visibleHeight: viewport)
            if tracker.policy != .hold { heldEveryStep = false }
        }
        return (heldEveryStep, maxDrift)
    }

    @Test("100 次增量全程保持 hold，锚点零漂移")
    func hundredIncrementsStayHeld() throws {
        let result = try runIncrements(count: 100, driftPerStep: 1)
        #expect(result.heldEveryStep, "100 次增量中只要有一次回到 follow，用户就会被拽到底部")
        #expect(result.maxDrift < 0.001, "锚点漂移 \(result.maxDrift) —— 累积漂移正是最隐蔽的 bug")
    }

    @Test("增量很大时也不被拉回")
    func largeIncrementsStayHeld() throws {
        let result = try runIncrements(count: 100, driftPerStep: 400)
        #expect(result.heldEveryStep)
        #expect(result.maxDrift < 0.001)
    }

    /// 贴底时才跟随 —— 与上一条互补，防止"为了不拉回用户"而把跟随也关掉。
    @Test("贴底时仍然跟随增量")
    func pinnedToTailStillFollows() {
        var tracker = TailTracker()
        // 距底部很小 → 在阈值内 → 跟随。
        tracker.update(distanceFromBottom: 10, visibleHeight: viewport)
        #expect(tracker.policy == .follow)
        #expect(!tracker.userScrolledUp)
    }

    /// 惯性滚动经过底部不能解除 hold。
    ///
    /// 这是 `update` 注释里点明的设计要点：一旦判定上翻，只有**真正**进入
    /// 底部阈值才恢复跟随；否则用户轻轻一滑就被拽走。
    @Test("轻微回弹不解锁 hold")
    func smallBounceDoesNotUnlock() {
        var tracker = TailTracker()
        tracker.markScrolledUp()
        // 距底部仍远大于阈值（800 * 0.15 = 120）。
        tracker.update(distanceFromBottom: 700, visibleHeight: viewport)
        #expect(tracker.policy == .hold, "回弹到 700 点仍应保持 hold")
        // 真正接近底部才解锁。
        tracker.update(distanceFromBottom: 100, visibleHeight: viewport)
        #expect(tracker.policy == .follow)
    }

    /// 显式"回到最新"必须立刻恢复跟随（用户点了入口或自己发完消息）。
    @Test("jumpToLatest 立即恢复跟随")
    func jumpToLatestRestoresFollow() {
        var tracker = TailTracker()
        tracker.markScrolledUp()
        #expect(tracker.policy == .hold)
        tracker.jumpToLatest()
        #expect(tracker.policy == .follow)
    }
}
