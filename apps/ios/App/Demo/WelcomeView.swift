import SwiftUI

/// Existing offline demo entry; production pairing is owned by PairingFlowView.
struct WelcomeView: View {
    var staticSnapshot = false
    @State private var demo = false

    var body: some View {
        let page = PairingWelcomePage(demo: { demo = true }, staticSnapshot: staticSnapshot)
        if staticSnapshot {
            // No pairing `.task`, no glass demo destination, no navigation-bar material, no animation.
            page
                .toolbar(.hidden, for: .navigationBar)
                .transaction { $0.disablesAnimations = true }
        } else {
            page.navigationDestination(isPresented: $demo) { DemoSceneList() }
        }
    }
}
