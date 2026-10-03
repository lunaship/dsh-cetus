import SwiftUI

struct RootView: View {
    var body: some View {
        NavigationStack {
            EmptyView()
        }
    }
}

@main
struct DeepLinksApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @State private var observedPhase: ScenePhase = .active

    var body: some Scene {
        WindowGroup {
            RootView()
                .onChange(of: scenePhase) { _, newPhase in
                    observedPhase = newPhase
                }
        }
    }
}
