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

    @Test func returningToAncestorRevealsTheFolderWeCameFrom() {
        #expect(filesReturnAnchorName(from: "src/app/ui", to: "src") == "app")
        #expect(filesReturnAnchorName(from: "src/app", to: "") == "src")
        #expect(filesReturnAnchorName(from: "文档/设计", to: "文档") == "设计")
        #expect(filesReturnAnchorName(from: "src", to: "src/app") == nil)
        #expect(filesReturnAnchorName(from: "src/app", to: "lib") == nil)
        #expect(filesReturnAnchorName(from: "src", to: "src") == nil)
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

    @Test func previewPathRequiresTheKeyAndAPreviewID() {
        let key = String(repeating: "ab", count: 16)
        let id = String(repeating: "cd", count: 12)
        let mapped = mapPreviewPath(key: key, pathAndQuery: "/\(key)/\(id)/assets/app.js?x=1")
        #expect(mapped == "/dsh-link/mobile/preview/\(id)/assets/app.js?x=1")
        #expect(mapPreviewPath(key: key, pathAndQuery: "/other/\(id)/") == nil)
        #expect(mapPreviewPath(key: key, pathAndQuery: "/\(key)/not-an-id/") == nil)
        #expect(mapPreviewPath(key: key, pathAndQuery: "http://127.0.0.1/\(key)/\(id)/") == nil)
        #expect(mapPreviewPath(key: key, pathAndQuery: "/\(key)/\(id)/../secret") == nil)
        #expect(previewLoopbackURL(port: 9, key: key, previewID: id).hasPrefix("http://127.0.0.1:9/"))
        #expect(previewBindHost == "127.0.0.1")
    }

    @Test func fileKindAndPreviewNavigation() {
        #expect(filePreviewKind(path: "a.swift", mime: nil) == .text)
        #expect(filePreviewKind(path: "a.bin", mime: "image/png") == .image)
        #expect(filePreviewKind(path: "a.pdf", mime: "application/pdf") == .quickLook)
        let local = URL(string: "http://127.0.0.1:9/key/id/")!
        #expect(previewNavigationAllowed(local, loopbackPort: 9))
        #expect(!previewNavigationAllowed(URL(string: "https://example.com")!, loopbackPort: 9))
        #expect(webSocketAccept("dGhlIHNhbXBsZSBub25jZQ==") == "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=")
        let frame = Data([0x81, 0x82, 0, 0, 0, 0, 0x68, 0x69])
        #expect(readClientFrame(frame)?.frame.payload == Data("hi".utf8))
    }
}
