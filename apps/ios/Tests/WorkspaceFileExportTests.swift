import DLCore
import DLModels
import DLSecurity
import Foundation
import Testing

@testable import Cetus

private actor WorkspaceDownloadService: ConversationServing {
    func downloadFile(sessionID: String, path: String) async throws -> DownloadedWorkspaceFile {
        guard sessionID == "test-session", path == "note.txt" else { throw ConversationServiceError.failed }
        return DownloadedWorkspaceFile(data: Data("from host".utf8), filename: path, contentType: "text/plain")
    }
}

private actor WorkspaceTreeService: ConversationServing {
    func tree(sessionID: String, path: String) async throws -> TreeResponse? {
        switch path {
        case "legacy": return nil
        case "broken": throw ConversationServiceError.failed
        case "empty": return TreeResponse(ok: true, path: "empty", entries: [])
        default:
            return TreeResponse(
                ok: true, path: path, truncated: path.isEmpty,
                entries: [TreeEntry(name: "src", type: .dir), TreeEntry(name: "说明.md", type: .file)])
        }
    }
}

@MainActor @Suite struct WorkspaceFileExportTests {
    private func waitForFiles(_ model: ConversationModel) async {
        for _ in 0..<200 where model.filesLoading { try? await Task.sleep(for: .milliseconds(5)) }
    }

    @Test func treeStatesStayDistinctAndReturnRevealsPreviousFolder() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let model = ConversationModel(
            hostID: "test-host", sessionID: "test-session", service: WorkspaceTreeService(),
            box: TranscriptSnapshotBox(keys: InMemorySecureStore(), directory: root), autostart: false)
        model.loadFiles(path: "")
        await waitForFiles(model)
        #expect(model.fileEntries?.count == 2)
        #expect(model.filesTruncated)
        model.loadFiles(path: "src/app")
        await waitForFiles(model)
        model.loadFiles(path: "")
        await waitForFiles(model)
        #expect(model.filesReturnAnchor == "src")
        model.loadFiles(path: "legacy")
        await waitForFiles(model)
        #expect(model.filesUnsupported && !model.filesError && model.fileEntries == nil)
        model.loadFiles(path: "broken")
        await waitForFiles(model)
        #expect(model.filesError && !model.filesUnsupported && model.fileEntries == nil)
        model.loadFiles(path: "empty")
        await waitForFiles(model)
        #expect(model.fileEntries == [] && !model.filesError && !model.filesUnsupported)
    }

    @Test func exportsStayInsideUniqueProtectedDirectories() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let download = DownloadedWorkspaceFile(
            data: Data("hello".utf8), filename: "../../%E4%B8%AD%E6%96%87.txt", contentType: "text/plain")
        let first = try WorkspaceFileExport.prepare(download, path: "src/中文.txt", root: root)
        let second = try WorkspaceFileExport.prepare(download, path: "src/中文.txt", root: root)
        let url = try #require(first.url)
        #expect(first.text == "hello")
        #expect(url.lastPathComponent == "中文.txt")
        #expect(
            url.deletingLastPathComponent().deletingLastPathComponent().standardizedFileURL.path
                == root.standardizedFileURL.path)
        #expect(url != second.url)
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        #expect((attributes[.posixPermissions] as? NSNumber)?.intValue == 0o600)
    }

    @Test func pdfAndInvalidTextAreNotRenderedAsUtf8() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let pdf = try WorkspaceFileExport.prepare(
            DownloadedWorkspaceFile(
                data: Data("%PDF-1.7".utf8), filename: "report.pdf", contentType: "application/pdf"),
            path: "report.pdf", root: root)
        #expect(pdf.text == nil)
        #expect(pdf.kind == .quickLook)
        let invalid = try WorkspaceFileExport.prepare(
            DownloadedWorkspaceFile(data: Data([255, 254]), filename: "bad.txt", contentType: "text/plain"),
            path: "bad.txt", root: root)
        #expect(invalid.text == nil)
    }

    @Test func launchCleanupKeepsFreshExportsAndExpiresOldOnes() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let file = try WorkspaceFileExport.prepare(
            DownloadedWorkspaceFile(data: Data("keep".utf8), filename: "keep.txt", contentType: "text/plain"),
            path: "keep.txt", root: root)
        let url = try #require(file.url)
        WorkspaceFileExport.expire(root: root)
        #expect(FileManager.default.fileExists(atPath: url.path))
        WorkspaceFileExport.expire(root: root, now: Date().addingTimeInterval(25 * 60 * 60))
        #expect(!FileManager.default.fileExists(atPath: url.path))
    }

    @Test func productionModelOpensDownloadedBytesAndKeepsFailureDistinct() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let model = ConversationModel(
            hostID: "test-host", sessionID: "test-session", service: WorkspaceDownloadService(),
            box: TranscriptSnapshotBox(keys: InMemorySecureStore(), directory: root), autostart: false)
        await model.openFile(path: "note.txt")
        let file = try #require(model.openedFile)
        let url = try #require(file.url)
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        #expect(file.text == "from host")
        #expect(!file.failed)
        #expect(!model.openingFile)
        await model.openFile(path: "missing")
        #expect(model.openedFile?.failed == true)
        #expect(model.openedFile?.url == nil)
    }

    @Test func oversizedExportsAreRejected() {
        let file = DownloadedWorkspaceFile(
            data: Data(count: WorkspaceFileExport.maximumBytes + 1), filename: "big.bin", contentType: nil)
        #expect(throws: ConversationServiceError.failed) {
            _ = try WorkspaceFileExport.prepare(file, path: "big.bin")
        }
    }
}
