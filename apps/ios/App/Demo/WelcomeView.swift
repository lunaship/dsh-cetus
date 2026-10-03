import DLUI
import SwiftUI

struct WelcomeView: View {
    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            demoLink
            #if DEBUG
                settingsLink
            #endif
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(DLColor.background)
    }

    private var demoLink: some View {
        NavigationLink {
            DemoSceneList()
        } label: {
            Text("Try the demo")
                .font(DLFont.body)
                .foregroundStyle(DLColor.accent)
                .frame(minHeight: 44)
        }
    }

    #if DEBUG
        private var settingsLink: some View {
            NavigationLink {
                DebugSettingsView()
            } label: {
                Text("Settings")
                    .font(DLFont.body)
                    .foregroundStyle(DLColor.accent)
                    .frame(minHeight: 44)
            }
        }
    #endif
}
