import Foundation
import Testing

@testable import Cetus

/// 首页副标题只放连接状态，不重复大标题里的电脑名（用户 2026-10-10 决定）。
@Suite struct InboxSubtitleTests {
    @Test func subtitleShowsOnlyLinkState() {
        let zh = InboxCopy(locale: Locale(identifier: "zh-Hans"))
        #expect(zh.subtitle(link: .online(.local)) == "● 在线")
        #expect(zh.subtitle(link: .online(.remote)) == "● 远程")
        #expect(zh.subtitle(link: .offline) == "● 离线")
        #expect(zh.subtitle(link: .checking(nil)) == "● 正在连接")

        let en = InboxCopy(locale: Locale(identifier: "en"))
        #expect(en.subtitle(link: .online(.local)) == "● Online")
        #expect(en.subtitle(link: .checking(.remote)) == "● Remote")
        #expect(en.subtitle(link: .offline) == "● Offline")
        #expect(en.subtitle(link: .checking(nil)) == "● Connecting")
    }
}
