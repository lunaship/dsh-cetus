import DLCore
import DLModels
import Foundation
import Testing

@Suite struct ReviewTests {
    @Test func pathPartsAndBreadcrumbs() {
        #expect(fileTitleParts("src/app/Main.swift") == ("src/app", "Main.swift"))
        #expect(fileTitleParts("Main.swift").name == "Main.swift")
        #expect(breadcrumbPaths("src/app") == ["", "src", "src/app"])
    }

    @Test func diffLinesMarkTheChangedWord() {
        var budget = intralineBudgetCells
        let lines = diffLines(
            from: [
                DiffHunk(
                    oldStart: 1, oldLines: 1, newStart: 1, newLines: 1,
                    lines: ["-call(a, b)", "+call(a, c)"])
            ],
            budget: &budget)
        let deleted = lines.first { $0.kind == .delete }
        let added = lines.first { $0.kind == .add }
        #expect(intralinePieces(deleted?.text ?? "", deleted?.emphasis ?? []) == ["b"])
        #expect(intralinePieces(added?.text ?? "", added?.emphasis ?? []) == ["c"])
    }

    @Test func sha256KeepsMatchingBytesAndDropsTheRest() {
        let bytes = Data("hello".utf8)
        let digest = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        #expect(sha256Matches(expectedHex: nil, bytes: bytes))
        #expect(sha256Matches(expectedHex: digest.uppercased(), bytes: bytes))
        #expect(acceptedDownload(bytes: bytes, sha256: "00") == nil)
        #expect(acceptedDownload(bytes: bytes, sha256: digest) == bytes)
    }

    @Test func previewPathRequiresTheKey() {
        #expect(previewTarget(path: "/secret/preview-1/index.html", key: "secret") == "preview-1")
        #expect(previewTarget(path: "/other/preview-1/", key: "secret") == nil)
        #expect(previewTarget(path: "/secret/", key: "secret") == nil)
        #expect(previewLoopbackURL(port: 9, key: "secret", previewID: "preview-1").hasPrefix("http://127.0.0.1:9/"))
        #expect(previewBindHost == "127.0.0.1")
    }
}
