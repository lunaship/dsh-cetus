import DLUI
import Testing
import UIKit

@Suite struct WideLayoutTests {
    @Test @MainActor func commandReturnSubmitsAndEscapeCloses() {
        let view = DLComposerView(sendTitle: "Send")
        var sent = 0
        var escaped = 0
        view.onSubmit = { sent += 1 }
        view.onEscape = { escaped += 1 }
        let commands = view.keyCommands ?? []
        #expect(commands.contains { $0.input == "\r" && $0.modifierFlags.contains(.command) })
        #expect(commands.contains { $0.input == UIKeyCommand.inputEscape })
        view.submitFromShortcut()
        view.escapeFromShortcut()
        #expect(sent == 1)
        #expect(escaped == 1)
    }
}
