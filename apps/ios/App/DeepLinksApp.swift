import SwiftUI

@main
struct DeepLinksApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @State private var observedPhase: ScenePhase = .active

    var body: some Scene {
        WindowGroup {
            NavigationStack {
                EmptyView()
            }
            .onChange(of: scenePhase) { _, newPhase in
                observedPhase = newPhase
            }
        }
    }
}
