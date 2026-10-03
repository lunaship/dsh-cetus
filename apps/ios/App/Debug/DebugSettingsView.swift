#if DEBUG
    import DLUI
    import SwiftUI

    struct DebugSettingsView: View {
        var body: some View {
            Form {
                Section {
                    NavigationLink(L10n.string("settings.scenes", fallback: "Component scenes")) {
                        DemoSceneList()
                    }
                } footer: {
                    Text(
                        L10n.string(
                            "settings.scenes.footer",
                            fallback: "Bundled fixtures only. These scenes do not use the network."
                        )
                    )
                    .font(DLFont.caption)
                }
            }
            .navigationTitle(L10n.string("settings.title", fallback: "Settings"))
        }
    }
#endif
