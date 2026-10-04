import SwiftUI
import UIKit

/// 1.2 rename content, shared by the production sheet and standalone snapshots.
struct PairingRenameForm: View {
    @Environment(\.locale) private var locale
    @Binding var newName: String
    let originalName: String
    var cancel: () -> Void = {}
    var retry: () -> Void = {}
    /// Snapshots pass false so the field never shows a caret. The production sheet stays focusable.
    var allowsFocus = true
    @FocusState private var focused: Bool

    var body: some View {
        let copy = PairingCopy(locale: locale)
        Form {
            Section {
                nameField(copy.text(.deviceName))
            } footer: {
                Text(copy.text(.renameBody))
            }
        }
        .navigationTitle(copy.text(.renameTitle))
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(copy.text(.cancel), action: cancel)
            }
            ToolbarItem(placement: .confirmationAction) {
                Button(copy.text(.retry), action: retry)
                    .disabled(
                        newName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            || newName.trimmingCharacters(in: .whitespacesAndNewlines) == originalName)
            }
        }
    }

    @ViewBuilder private func nameField(_ title: String) -> some View {
        let field = TextField(title, text: $newName).textInputAutocapitalization(.words)
        if allowsFocus {
            field
        } else {
            field
                .focused($focused)
                .tint(.clear)
                .allowsHitTesting(false)
                .background { RenameFocusSink() }
                .onAppear { focused = false }
        }
    }
}

/// Resigns first responder during snapshot layout, before the image is rendered.
private struct RenameFocusSink: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView { Sink() }
    func updateUIView(_ uiView: UIView, context: Context) {}

    private final class Sink: UIView {
        private var clearing = false

        override func layoutSubviews() {
            super.layoutSubviews()
            guard !clearing, let window else { return }
            clearing = true
            window.endEditing(true)
            hideCaret(in: window)
            clearing = false
        }

        private func hideCaret(in view: UIView) {
            if let field = view as? UITextField {
                field.tintColor = .clear
                field.resignFirstResponder()
            }
            view.subviews.forEach(hideCaret)
        }
    }
}
