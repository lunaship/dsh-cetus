#if DEBUG
    import DLUI
    import SwiftUI

    struct DebugSettingsView: View {
        var body: some View {
            Form {
                Section {
                    NavigationLink("Component scenes") {
                        DemoSceneList()
                    }
                } footer: {
                    Text("Bundled fixtures only. These scenes do not use the network.")
                        .font(DLFont.caption)
                }
            }
            .navigationTitle("Settings")
        }
    }
#endif
