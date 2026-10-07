import DLSecurity
import SwiftUI

/// 草稿仓库的生产依赖根（C02 要求 4）。
///
/// 旧代码把 `draftDirectory` 当成 `ConversationPage` 的可选参数，而生产路径走
/// `ConversationFlowView`，它的 init 根本不接受这个参数 —— 于是 `if let draftDirectory`
/// 恒为 nil，草稿在生产上静默不落盘。
///
/// 现在仓库走环境值，从依赖根（`CetusApp` → `RootView`）注入一次，
/// 紧凑导航、宽屏详情、分享预填三条路都取到同一份；谁都没注入时回退到生产默认位置，
/// 宁可落盘也不要再静默失效。
private struct ComposerDraftStoreKey: EnvironmentKey {
    /// 兜底：默认仍指向真实沙盒位置，保证"没显式注入也能落盘"。
    static let defaultValue: ComposerDraftStore = .live(keys: KeychainStore())
}

extension EnvironmentValues {
    var composerDraftStore: ComposerDraftStore {
        get { self[ComposerDraftStoreKey.self] }
        set { self[ComposerDraftStoreKey.self] = newValue }
    }
}

extension View {
    /// 从依赖根注入草稿仓库。单测传临时目录 + 内存密钥仓库。
    func composerDraftStore(_ store: ComposerDraftStore) -> some View {
        environment(\.composerDraftStore, store)
    }
}
