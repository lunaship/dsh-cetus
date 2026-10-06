import DLCore
import Testing

struct PrivacyCoverTests {
    @Test("只有离开前台才遮住页面")
    func inactiveAndBackgroundCover() {
        #expect(PrivacyCover.covers(.active) == false)
        #expect(PrivacyCover.covers(.inactive))
        #expect(PrivacyCover.covers(.background))
        #expect(PrivacyCover.covers(.inactive, screenshots: true) == false)
    }
}
