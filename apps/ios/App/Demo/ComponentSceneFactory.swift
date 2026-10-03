import DLUI
import SwiftUI
import UIKit

enum ComponentSceneKind: String, CaseIterable, Identifiable {
    case inbox
    case status
    case chip
    case process
    case code
    case empty
    case banner
    case composer
    case decision

    var id: String { rawValue }

    var title: String {
        switch self {
        case .inbox: L10n.string("scene.inbox", fallback: "Inbox")
        case .status: L10n.string("scene.status", fallback: "Status")
        case .chip: L10n.string("scene.chip", fallback: "Chip")
        case .process: L10n.string("scene.process", fallback: "Process")
        case .code: L10n.string("scene.code", fallback: "Code")
        case .empty: L10n.string("scene.empty", fallback: "Empty")
        case .banner: L10n.string("scene.banner", fallback: "Banner")
        case .composer: L10n.string("scene.composer", fallback: "Composer")
        case .decision: L10n.string("scene.decision", fallback: "Decision")
        }
    }
}

enum ComponentSceneState: String, CaseIterable, Identifiable {
    case standard
    case long
    case disabled

    var id: String { rawValue }

    var showsLongText: Bool { self == .long }

    var isEnabled: Bool { self != .disabled }

    var title: String {
        switch self {
        case .standard: L10n.string("scene.state.standard", fallback: "Standard")
        case .long: L10n.string("scene.state.long", fallback: "Long text")
        case .disabled: L10n.string("scene.state.disabled", fallback: "Disabled")
        }
    }
}

enum ComponentSceneFactory {
    private static let fixtures: DemoFixtures = {
        do {
            return try DemoFixtures.loadFromBundle()
        } catch {
            fatalError("Demo fixtures are not in the app bundle: \(error)")
        }
    }()

    static func inbox(long: Bool, enabled: Bool) -> DLInboxRow {
        let item = fixtures.inbox
        let longText = fixtures.longText
        return DLInboxRow(
            title: long ? longText : item.title,
            workspace: long ? longText : item.workspace,
            time: item.time,
            status: item.status,
            preview: long ? longText : item.preview,
            isEnabled: enabled
        )
    }

    static func status(long: Bool, enabled: Bool) -> DLStatusSlot {
        let item = fixtures.status
        let longText = fixtures.longText
        return DLStatusSlot(
            title: long ? longText : item.title,
            meta: long ? longText : item.meta,
            isEnabled: enabled
        )
    }

    static func chip(long: Bool, enabled: Bool) -> DLChip {
        let title = long ? fixtures.longText : fixtures.chip.title
        return DLChip(title, style: .bordered, isEnabled: enabled) {}
    }

    static func process(long: Bool, enabled: Bool) -> DLProcessLine {
        DLProcessLine(long ? fixtures.longText : fixtures.process.text, isEnabled: enabled)
    }

    static func code(long: Bool, enabled: Bool) -> DLCodeBlock {
        DLCodeBlock(
            long ? fixtures.longText : fixtures.code.text,
            isEnabled: enabled,
            copyTitle: L10n.string("code.copy", fallback: "Copy")
        )
    }

    static func empty(long: Bool, enabled: Bool) -> DLEmptyState {
        let item = fixtures.empty
        let longText = fixtures.longText
        return DLEmptyState(
            title: long ? longText : item.title,
            systemImage: item.systemImage,
            message: long ? longText : item.message,
            isEnabled: enabled
        )
    }

    static func banner(long: Bool, enabled: Bool) -> DLBanner {
        DLBanner(long ? fixtures.longText : fixtures.banner.text, isEnabled: enabled)
    }

    static func composer(long: Bool, enabled: Bool) -> DLComposerView {
        DLComposerView(
            text: long ? fixtures.longText : fixtures.composer.text,
            isEnabled: enabled,
            sendTitle: L10n.string("composer.send", fallback: "Send")
        )
    }

    static func decision(long: Bool, enabled: Bool) -> DLDecisionBar {
        let item = fixtures.decision
        return DLDecisionBar(
            status: item.status,
            question: long ? fixtures.longText : item.question,
            secondaryTitle: item.secondaryTitle,
            primaryTitle: item.primaryTitle,
            isEnabled: enabled
        )
    }
}
