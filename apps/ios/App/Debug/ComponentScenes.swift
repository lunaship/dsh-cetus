#if DEBUG
    import DLUI
    import SwiftUI
    import UIKit

    enum ComponentScenes {
        private static let longText =
            "This line is intentionally long so the component shows truncation or wrapping instead of a short label."

        static func inbox(long: Bool, enabled: Bool) -> DLInboxRow {
            DLInboxRow(
                title: long ? longText : "Review the diff",
                workspace: long ? longText : "notes",
                time: "2:14 PM",
                status: "Waiting",
                preview: long ? longText : "Allow once to edit the README",
                isEnabled: enabled
            )
        }

        static func status(long: Bool, enabled: Bool) -> DLStatusSlot {
            DLStatusSlot(
                title: long ? longText : "Offline",
                meta: long ? longText : "Retry",
                isEnabled: enabled
            )
        }

        static func chip(long: Bool, enabled: Bool) -> DLChip {
            DLChip(long ? longText : "Allow once", style: .bordered, isEnabled: enabled) {}
        }

        static func process(long: Bool, enabled: Bool) -> DLProcessLine {
            DLProcessLine(long ? longText : "Thought 6s · Read 4 files ›", isEnabled: enabled)
        }

        static func code(long: Bool, enabled: Bool) -> DLCodeBlock {
            DLCodeBlock(long ? longText : "git status", isEnabled: enabled)
        }

        static func empty(long: Bool, enabled: Bool) -> DLEmptyState {
            DLEmptyState(
                title: long ? longText : "No sessions",
                systemImage: "tray",
                message: long ? longText : "Sessions you start will show up here.",
                isEnabled: enabled
            )
        }

        static func banner(long: Bool, enabled: Bool) -> DLBanner {
            DLBanner(
                long ? longText : "Offline. Messages will send when you reconnect.",
                isEnabled: enabled
            )
        }

        static func composer(long: Bool, enabled: Bool) -> DLComposerView {
            DLComposerView(text: long ? longText : "Draft", isEnabled: enabled)
        }

        static func decision(long: Bool, enabled: Bool) -> DLDecisionBar {
            DLDecisionBar(
                status: "Waiting",
                question: long ? longText : "Allow this command once?",
                secondaryTitle: "Don't allow",
                primaryTitle: "Allow once",
                isEnabled: enabled
            )
        }
    }
#endif
