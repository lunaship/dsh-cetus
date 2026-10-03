import Foundation
import Testing

struct TokenUsageTests {
    @Test func appAndDLUIAvoidRawColorsAndFixedFonts() throws {
        let iosRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let roots = [
            iosRoot.appendingPathComponent("App", isDirectory: true),
            iosRoot.appendingPathComponent("Packages/DLUI", isDirectory: true)
        ]
        let forbidden = [
            "Color(red:",
            "UIColor(red:",
            ".font(.system(size:"
        ]
        var scanned = 0
        var violations: [String] = []
        for root in roots {
            var isDirectory: ObjCBool = false
            #expect(
                FileManager.default.fileExists(atPath: root.path, isDirectory: &isDirectory)
                    && isDirectory.boolValue
            )
            guard
                let enumerator = FileManager.default.enumerator(
                    at: root,
                    includingPropertiesForKeys: [.isRegularFileKey],
                    options: [.skipsHiddenFiles]
                )
            else {
                Issue.record("cannot enumerate \(root.path)")
                continue
            }
            for case let fileURL as URL in enumerator {
                guard fileURL.pathExtension == "swift" else { continue }
                if fileURL.path.contains("/DLUI/Theme/") { continue }
                scanned += 1
                let text = try String(contentsOf: fileURL, encoding: .utf8)
                for token in forbidden where text.contains(token) {
                    violations.append("\(fileURL.path) contains \(token)")
                }
            }
        }
        #expect(scanned > 0)
        #expect(violations.isEmpty)
    }
}
