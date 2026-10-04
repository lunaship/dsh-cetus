import SwiftUI

/// 1.2 rename content, shared by the production sheet and standalone snapshots.
struct PairingRenameForm: View {
    @Environment(\.locale) private var locale
    @Binding var newName: String
    let originalName: String
    var cancel: () -> Void = {}
    var retry: () -> Void = {}

    var body: some View {
        let copy = PairingCopy(locale: locale)
        Form {
            Section {
                TextField(copy.text(.deviceName), text: $newName)
                    .textInputAutocapitalization(.words)
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
}
