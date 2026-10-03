import DLUI
import SwiftUI
import UIKit

struct DemoSceneList: View {
    var body: some View {
        List {
            ForEach(ComponentSceneKind.allCases) { kind in
                Section(kind.title) {
                    ForEach(ComponentSceneState.allCases) { state in
                        NavigationLink(state.title) {
                            ComponentScenePage(kind: kind, state: state)
                        }
                    }
                }
            }
        }
        .navigationTitle("Demo")
    }
}

struct ComponentScenePage: View {
    let kind: ComponentSceneKind
    let state: ComponentSceneState

    var body: some View {
        ScrollView {
            content
                .padding(16)
        }
        .background(DLColor.background)
        .navigationTitle(kind.title)
    }

    @ViewBuilder private var content: some View {
        switch kind {
        case .inbox:
            ComponentSceneFactory.inbox(long: state.showsLongText, enabled: state.isEnabled)
        case .status:
            ComponentSceneFactory.status(long: state.showsLongText, enabled: state.isEnabled)
        case .chip:
            ComponentSceneFactory.chip(long: state.showsLongText, enabled: state.isEnabled)
        case .process:
            ComponentSceneFactory.process(long: state.showsLongText, enabled: state.isEnabled)
        case .code:
            ComponentSceneFactory.code(long: state.showsLongText, enabled: state.isEnabled)
        case .empty:
            ComponentSceneFactory.empty(long: state.showsLongText, enabled: state.isEnabled)
                .frame(minHeight: 280)
        case .banner:
            ComponentSceneFactory.banner(long: state.showsLongText, enabled: state.isEnabled)
        case .composer:
            ComposerSceneHost(long: state.showsLongText, enabled: state.isEnabled)
                .frame(height: 96)
        case .decision:
            DecisionSceneHost(long: state.showsLongText, enabled: state.isEnabled)
                .frame(height: 220)
        }
    }
}

private struct ComposerSceneHost: UIViewRepresentable {
    var long: Bool
    var enabled: Bool

    func makeUIView(context: Context) -> DLComposerView {
        ComponentSceneFactory.composer(long: long, enabled: enabled)
    }

    func updateUIView(_ uiView: DLComposerView, context: Context) {}
}

private struct DecisionSceneHost: UIViewRepresentable {
    var long: Bool
    var enabled: Bool

    func makeUIView(context: Context) -> DLDecisionBar {
        ComponentSceneFactory.decision(long: long, enabled: enabled)
    }

    func updateUIView(_ uiView: DLDecisionBar, context: Context) {}
}
