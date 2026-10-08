import Foundation
import Testing

@testable import Cetus

struct DemoFixturesTests {
    @Test func bundledSceneDataMatchesScreenshotCopy() throws {
        let fixtures = try DemoFixtures.load(from: fixturesURL())
        let expectedLong =
            "This line is intentionally long so the component shows truncation or wrapping instead of a short label."
        #expect(fixtures.longText == expectedLong)
        #expect(fixtures.inbox.title == "Review the diff")
        #expect(fixtures.inbox.workspace == "notes")
        #expect(fixtures.inbox.time == "2:14 PM")
        #expect(fixtures.inbox.status == "Waiting")
        #expect(fixtures.inbox.preview == "Allow once to edit the README")
        #expect(fixtures.status.title == "Offline")
        #expect(fixtures.status.meta == "Retry")
        #expect(fixtures.chip.title == "Allow once")
        #expect(fixtures.process.text == "Thought 6s · Read 4 files ›")
        #expect(fixtures.code.text == "git status")
        #expect(fixtures.empty.title == "No sessions")
        #expect(fixtures.empty.systemImage == "tray")
        #expect(fixtures.empty.message == "Sessions you start will show up here.")
        #expect(fixtures.banner.text == "Offline. Messages will send when you reconnect.")
        #expect(fixtures.composer.text == "Draft")
        #expect(fixtures.decision.status == "Waiting")
        #expect(fixtures.decision.question == "Allow this command once?")
        #expect(fixtures.decision.secondaryTitle == "Don't allow")
        #expect(fixtures.decision.primaryTitle == "Allow once")
        #expect(ComponentSceneKind.allCases.count == 9)
        #expect(ComponentSceneState.allCases.count == 3)
        let bundled = try DemoFixtures.loadFromBundle()
        #expect(bundled == fixtures)
    }

    @Test func demoAndDebugSourcesDoNotUseTheNetwork() throws {
        let forbidden = ["URLSession", "URLRequest", "http://", "https://", "NWConnection"]
        var scanned = 0
        var violations: [String] = []
        for name in ["Demo", "Debug"] {
            let root = iosRoot().appendingPathComponent("App").appendingPathComponent(name)
            guard
                let enumerator = FileManager.default.enumerator(
                    at: root,
                    includingPropertiesForKeys: nil,
                    options: [.skipsHiddenFiles]
                )
            else {
                Issue.record("cannot enumerate \(root.path)")
                continue
            }
            for case let file as URL in enumerator {
                guard file.pathExtension == "swift" else { continue }
                scanned += 1
                let text = try String(contentsOf: file, encoding: .utf8)
                for token in forbidden where text.contains(token) {
                    violations.append("\(file.lastPathComponent) contains \(token)")
                }
            }
        }
        #expect(scanned > 0)
        #expect(violations.isEmpty)
    }

    private func fixturesURL() -> URL {
        iosRoot()
            .appendingPathComponent("App")
            .appendingPathComponent("Demo")
            .appendingPathComponent("fixtures")
            .appendingPathComponent("components.json")
    }

    private func iosRoot() -> URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }
}
