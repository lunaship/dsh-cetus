import DLCore
import DLModels
import DLUI
import SnapshotTesting
import SwiftUI
import UIKit
import XCTest

@testable import DeepLinks

@MainActor final class InboxSnapshotTests: XCTestCase {
    nonisolated override func invokeTest() {
        withSnapshotTesting(record: snapshotRecordMode()) {
            super.invokeTest()
        }
    }

    func testInbox() { matrix("2_1_inbox") { page(.inbox, language: $0) } }
    func testEmpty() { matrix("2_2_empty") { page(.empty, language: $0) } }
    func testOffline() { matrix("2_3_offline") { page(.offline, language: $0) } }
    func testSearch() { matrix("2_4_search") { page(.search, language: $0) } }
    func testContext() { matrix("2_6_context") { context($0) } }

    func testSuggestions() { oneScene("2_4_suggestions") { InboxSuggestionSnapshot(items: ["审批", "发布"]) } }
    func testSearchEmpty() { oneScene("2_4_empty") { page(.searchEmpty, language: "zh-Hans") } }
    func testSearchDegraded() { oneScene("2_4_degraded") { page(.degraded, language: "zh-Hans") } }
    func testWorkspaceEmpty() { oneScene("2_2_workspace") { page(.workspace, language: "zh-Hans") } }
    func testMenu() { oneScene("2_5_menu") { InboxMenuSnapshot(model: populated("zh-Hans")) } }
    func testArchived() {
        oneScene("2_5_archived") { NavigationStack { InboxArchivedPage(model: archived("zh-Hans")) } }
    }
    func testDelete() { oneScene("2_6_delete") { InboxDeleteSnapshot(name: "等你审批发布") } }
    func testRename() { oneScene("2_6_rename") { InboxRenameSnapshot(name: "等你审批发布") } }
    func testLoading() { oneScene("2_1_loading") { page(.loading, language: "zh-Hans") } }
    func testFiltered() { oneScene("2_1_filtered") { page(.filtered, language: "zh-Hans") } }

    private func page(_ kind: InboxFixtureKind, language: String) -> some View {
        InboxPage(model: fixture(kind, language: language), staticSnapshot: true)
    }

    private func context(_ language: String) -> some View {
        InboxContextSnapshot(session: approvalSession(language), model: populated(language))
    }

    private func matrix<V: View>(_ scene: String, make: (String) -> V) {
        for language in ["zh-Hans", "en"] {
            for appearance in [UIUserInterfaceStyle.light, .dark] {
                for large in [false, true] {
                    render(
                        scene, appearance: appearance, language: language, large: large, navigation: false,
                        make: { make(language) })
                }
            }
        }
    }

    private func oneScene<V: View>(_ scene: String, make: () -> V) {
        render(scene, appearance: .light, language: "zh-Hans", large: false, navigation: false, make: make)
    }

    private func render<V: View>(
        _ scene: String, appearance: UIUserInterfaceStyle, language: String, large: Bool, navigation: Bool,
        make: () -> V
    ) {
        let content = Group {
            if navigation { NavigationStack { make() } } else { make() }
        }
        .environment(\.locale, Locale(identifier: language))
        .environment(\.dynamicTypeSize, large ? DynamicTypeSize.accessibility3 : DynamicTypeSize.large)
        .tint(DLColor.accent)
        .transaction { $0.disablesAnimations = true }
        let traits = UITraitCollection(traitsFrom: [
            UITraitCollection(userInterfaceStyle: appearance),
            UITraitCollection(userInterfaceIdiom: .phone),
            UITraitCollection(
                preferredContentSizeCategory: large
                    ? UIContentSizeCategory.accessibilityExtraLarge : UIContentSizeCategory.large),
        ])
        assertSnapshot(
            of: content, as: .image(layout: .fixed(width: 402, height: 874), traits: traits),
            named: large ? "large" : "default",
            testName: snapshotName(scene, appearance: appearance, language: language))
    }

    private func snapshotName(_ scene: String, appearance: UIUserInterfaceStyle, language: String) -> String {
        "Snapshot_\(scene)_\(appearance == .dark ? "dark" : "light")_\(language == "en" ? "en" : "zh")"
    }
}

private enum InboxFixtureKind {
    case inbox
    case empty
    case offline
    case search
    case searchEmpty
    case degraded
    case workspace
    case loading
    case filtered
}

@MainActor private func fixture(_ kind: InboxFixtureKind, language: String) -> InboxModel {
    let model = base(language)
    switch kind {
    case .inbox, .offline, .filtered:
        fill(model, language: language)
        if kind == .offline { model.link = .offline }
        if kind == .filtered { model.filter = .running }
    case .empty:
        model.link = .online(.local)
    case .search:
        fill(model, language: language)
        let zh = !language.hasPrefix("en")
        model.query = zh ? "审批" : "approval"
        model.useSearchResult(
            InboxSearchPayload(
                items: [SessionSearchItem(sessionId: "done", snippet: zh ? "正文里的审批" : "approval in the body")],
                degraded: false))
    case .searchEmpty:
        fill(model, language: language)
        model.query = "zzzz-no-hit"
        model.useSearchResult(InboxSearchPayload(items: [], degraded: false))
    case .degraded:
        fill(model, language: language)
        model.query = "审批"
        model.useSearchResult(InboxSearchPayload(items: [], degraded: true))
    case .workspace:
        fill(model, language: language)
        model.setWorkspace("/other/empty")
        model.workspaces.append(WorkspaceInfo(path: "/other/empty", title: "empty"))
    case .loading:
        model.link = .checking(nil)
        model.sessions = []
    }
    return model
}

@MainActor private func populated(_ language: String) -> InboxModel {
    let model = base(language)
    fill(model, language: language)
    model.computers = [
        InboxComputer(id: "host-a", name: language.hasPrefix("en") ? "Studio" : "工作室"),
        InboxComputer(id: "host-b", name: "Mini"),
    ]
    return model
}

@MainActor private func archived(_ language: String) -> InboxModel {
    let model = populated(language)
    model.useArchived(["done"])
    return model
}

@MainActor private func base(_ language: String) -> InboxModel {
    let suite = "inbox-snap-\(language)-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defaults.removePersistentDomain(forName: suite)
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(secondsFromGMT: 0)!
    let model = InboxModel(
        hostID: "host-a", service: InboxPreviewService(), cache: InboxMemoryCache(),
        preferences: InboxPreferences(defaults: defaults), autostart: false,
        now: Date(timeIntervalSince1970: 1_780_000_000), calendar: calendar)
    model.computerName = language.hasPrefix("en") ? "Studio" : "工作室"
    model.link = .online(.local)
    model.workspaces = [WorkspaceInfo(path: "/work/app", sessionIds: ["approve", "ask", "run", "done"])]
    return model
}

@MainActor private func fill(_ model: InboxModel, language: String) {
    let zh = !language.hasPrefix("en")
    let now = 1_780_000_000
    model.sessions = [
        SessionSummary(
            sessionId: "approve", title: zh ? "等你审批发布" : "Needs approval", updatedAt: now - 120,
            running: true, cwd: "/work/app", awaitingInput: true),
        SessionSummary(
            sessionId: "ask", title: zh ? "确认方案" : "Confirm the plan", updatedAt: now - 600, running: true,
            cwd: "/work/app", awaitingInput: true),
        SessionSummary(
            sessionId: "run", title: zh ? "跑测试" : "Run tests", updatedAt: now - 1_200, running: true,
            cwd: "/work/app", subagentCount: 2, activity: SessionActivity(kind: .tool, label: "npm test", step: 3)),
        SessionSummary(
            sessionId: "done", title: zh ? "改完了" : "Finished the change", updatedAt: now - 2_400,
            cwd: "/work/app", lastResult: SessionLastResult(text: zh ? "修了登录" : "Fixed login", files: 2)),
    ]
    model.phoneAction = .approval(sessionID: "approve", approvalID: "a1", toolName: "bash deploy")
}

private func approvalSession(_ language: String) -> SessionSummary {
    SessionSummary(sessionId: "approve", title: language.hasPrefix("en") ? "Needs approval" : "等你审批发布")
}

private actor InboxPreviewService: InboxServing {}

private struct InboxContextSnapshot: View {
    var session: SessionSummary
    var model: InboxModel
    @Environment(\.locale) private var locale

    var body: some View {
        NavigationStack {
            List { inboxSessionActions(session, model: model, copy: InboxCopy(locale: locale)) }
                .navigationTitle(InboxCopy(locale: locale).text(.brand))
        }
    }
}

private struct InboxMenuSnapshot: View {
    var model: InboxModel
    @Environment(\.locale) private var locale

    var body: some View {
        let copy = InboxCopy(locale: locale)
        NavigationStack {
            List {
                Section(copy.text(.computers)) {
                    ForEach(model.computerRows(copy: copy)) { row in
                        HStack {
                            Text(row.title)
                            Spacer()
                            if row.current { Image(systemName: "checkmark") }
                        }
                    }
                }
                Section(copy.text(.workspaces)) {
                    if model.tokens.isEmpty {
                        Label(copy.text(.allWorkspaces), systemImage: "checkmark")
                    } else {
                        Text(copy.text(.allWorkspaces))
                    }
                    ForEach(inboxVisibleWorkspaces(model.workspaces), id: \.self) { path in
                        Text(inboxWorkspaceName(path) ?? path)
                    }
                    Text(copy.text(.addWorkspace))
                    Text(copy.text(.archived))
                }
            }
            .navigationTitle(copy.text(.brand))
        }
    }
}

private struct InboxSuggestionSnapshot: View {
    var items: [String]
    @Environment(\.locale) private var locale

    var body: some View {
        let copy = InboxCopy(locale: locale)
        NavigationStack {
            List {
                Section(copy.text(.searchRecent)) {
                    ForEach(items, id: \.self) { Text($0) }
                }
            }
            .navigationTitle(copy.text(.searchPrompt))
        }
    }
}

private struct InboxDeleteSnapshot: View {
    var name: String
    @Environment(\.locale) private var locale

    var body: some View {
        let copy = InboxCopy(locale: locale)
        VStack(alignment: .leading, spacing: 16) {
            Text(copy.text(.deleteTitle)).font(.title3.bold())
            Text(copy.format(.deleteMessage, name))
            Button(copy.text(.delete), role: .destructive) {}
            Button(copy.text(.cancel), role: .cancel) {}
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
    }
}

private struct InboxRenameSnapshot: View {
    var name: String
    @Environment(\.locale) private var locale

    var body: some View {
        let copy = InboxCopy(locale: locale)
        VStack(alignment: .leading, spacing: 16) {
            Text(copy.text(.rename)).font(.title3.bold())
            Text(name).font(.body)
            Text(copy.text(.renameHint)).foregroundStyle(DLColor.secondaryLabel)
            Button(copy.text(.rename)) {}
            Button(copy.text(.cancel), role: .cancel) {}
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
    }
}
