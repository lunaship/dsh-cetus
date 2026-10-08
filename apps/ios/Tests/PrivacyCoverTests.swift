import DLCore
import Foundation
import Testing

struct PrivacyCoverTests {
    @Test("只有离开前台才遮住页面")
    func inactiveAndBackgroundCover() {
        #expect(PrivacyCover.covers(.active) == false)
        #expect(PrivacyCover.covers(.inactive))
        #expect(PrivacyCover.covers(.background))
        #expect(PrivacyCover.covers(.inactive, screenshots: true) == false)
    }

    // MARK: - C13 要求 6：恢复时解除，不能变成永久白屏

    @Test("回到 active 时必须解除遮罩")
    func releasingOnActive() {
        // shouldRelease 与 covers 严格互补 —— 两条规则必须同时成立，
        // 否则会出现「盖住了但永不解除」的永久白屏。
        for visibility in [AppVisibility.active, .inactive, .background] {
            #expect(
                PrivacyCover.shouldRelease(visibility) == (PrivacyCover.covers(visibility) == false),
                "covers / shouldRelease 必须互补：\(visibility)")
        }
    }

    @Test("inactive 与 background 都不解除，active 才解除")
    func releaseOnlyWhenActive() {
        #expect(PrivacyCover.shouldRelease(.active))
        #expect(PrivacyCover.shouldRelease(.inactive) == false)
        #expect(PrivacyCover.shouldRelease(.background) == false)
    }

    @Test("截图模式永不遮盖，也就无需留下遮罩")
    func screenshotsNeverCoverNorLinger() {
        for visibility in [AppVisibility.active, .inactive, .background] {
            #expect(PrivacyCover.covers(visibility, screenshots: true) == false)
            #expect(PrivacyCover.shouldRelease(visibility, screenshots: true))
        }
    }

    @Test("遮罩卡住超过阈值后允许手动解除（永久白屏的兜底出口）")
    func manualEscapeAfterThreshold() {
        let covered = Date(timeIntervalSince1970: 1_000)

        // 刚盖上：不给出口（正常的一次切走再回来是秒级）
        #expect(
            PrivacyCover.canEscapeManually(
                coveredSince: covered, now: covered.addingTimeInterval(1)) == false)
        #expect(
            PrivacyCover.canEscapeManually(
                coveredSince: covered, now: covered.addingTimeInterval(29)) == false)

        // 到阈值：给出口
        #expect(
            PrivacyCover.canEscapeManually(
                coveredSince: covered, now: covered.addingTimeInterval(30)))
        #expect(
            PrivacyCover.canEscapeManually(
                coveredSince: covered, now: covered.addingTimeInterval(600)))

        // 没盖的时候没有「解除」可言
        #expect(PrivacyCover.canEscapeManually(coveredSince: nil, now: covered) == false)
    }

    @Test("默认阈值是 30 秒量级，不是几分钟")
    func defaultThresholdIsReasonable() {
        // 太长 = 用户体验等同死路；太短 = 正常权限弹窗期间就被解除。
        #expect(PrivacyCover.manualEscapeAfter >= 10)
        #expect(PrivacyCover.manualEscapeAfter <= 60)
    }
}
