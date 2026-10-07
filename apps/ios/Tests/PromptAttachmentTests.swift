import DLCore
import Foundation
import Testing

@testable import Cetus

@Suite struct PromptAttachmentTests {
    @Test func documentImageKeepsOriginalBytesWithoutCropping() throws {
        let png = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02])
        let decision = classifyPromptAttachment(bytes: png, declaredMediaType: "image/png", existingCount: 0)
        guard case .image(let image) = decision else {
            Issue.record("expected image")
            return
        }
        #expect(image.mediaType == "image/png")
        #expect(image.data == png.base64EncodedString())
        #expect(!promptImageCroppingAvailable)
        #expect(promptDocumentTypes == [.item])
    }

    @Test func fileSourceRejectsNonImages() {
        let text = Data("not an image".utf8)
        #expect(
            classifyPromptAttachment(bytes: text, declaredMediaType: "text/plain", existingCount: 0)
                == .rejected(
                    .unsupported))
        #expect(
            classifyPromptAttachment(bytes: Data(), declaredMediaType: "image/png", existingCount: 0)
                == .rejected(
                    .unsupported))
    }

    @Test func jpegGifAndWebpUseFileHeaders() {
        #expect(media(Data([0xFF, 0xD8, 0xFF, 0x61]), declared: nil) == "image/jpeg")
        #expect(media(Data("GIF89a".utf8), declared: "image/jpg") == "image/gif")
        var webp = Data("RIFF".utf8)
        webp.append(contentsOf: [0, 0, 0, 0])
        webp.append(contentsOf: Data("WEBP".utf8))
        guard
            case .image(let image) = classifyPromptAttachment(
                bytes: webp, declaredMediaType: nil, existingCount: 0)
        else {
            Issue.record("webp")
            return
        }
        #expect(image.mediaType == "image/webp")
        #expect(normalizedPromptMediaType(" Image/JPG ; charset=binary ") == "image/jpeg")
    }

    @Test func sizeAndCountMatchPluginLimits() {
        let png = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
        guard
            case .image(let fourth) = classifyPromptAttachment(
                bytes: png, declaredMediaType: nil, existingCount: promptImageLimit - 1)
        else {
            Issue.record("fourth image")
            return
        }
        #expect(fourth.data == png.base64EncodedString())
        #expect(
            classifyPromptAttachment(bytes: png, declaredMediaType: nil, existingCount: promptImageLimit)
                == .rejected(.limit))
        let oversized = Data(count: (promptImageDataLimit / 4) * 3 + 3)
        #expect(
            classifyPromptAttachment(bytes: oversized, declaredMediaType: "image/png", existingCount: 3)
                == .rejected(.tooLarge))
    }

    @Test func promptBodyOmitsImagesUntilOneIsAttached() throws {
        let empty = try JSONDecoder().decode(
            Probe.self, from: encodePromptRequest(text: "hi", mode: "queue", images: []))
        #expect(empty.text == "hi")
        #expect(empty.mode == "queue")
        #expect(empty.images == nil)
        let image = PromptImage(mediaType: "image/png", data: "YQ==")
        let filled = try JSONDecoder().decode(
            Probe.self, from: encodePromptRequest(text: "", mode: "queue", images: [image]))
        #expect(filled.images == [image])
    }

    @Test func scopedFileReadUsesOnlyTheTemporaryDirectory() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
            UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let png = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x11])
        let file = directory.appendingPathComponent("shot.png")
        try png.write(to: file, options: .noFileProtection)
        let missing = directory.appendingPathComponent("gone.png")

        let read = try #require(try? readPromptFile(at: file).get())
        #expect(read.bytes == png)
        #expect(read.declaredMediaType == "image/png")
        #expect(readPromptFile(at: missing) == .failure(.unreadable))
        #expect(promptFileRejection(.unreadable) == .unreadable)
        #expect(declaredPromptMediaType(for: directory.appendingPathComponent("note.txt")) == "text/plain")
    }

    @Test func acceptedFilesKeepImagesAndTheFirstRejection() {
        let png = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x22])
        let files: [Result<PickedPromptFile, PromptFileReadFailure>] = [
            .success(PickedPromptFile(bytes: png, declaredMediaType: "image/png")),
            .success(PickedPromptFile(bytes: Data("notes".utf8), declaredMediaType: "text/plain")),
            .failure(.unreadable),
        ]
        let accepted = acceptPromptFiles(files, existing: [])
        #expect(accepted.images.count == 1)
        #expect(accepted.images[0].mediaType == "image/png")
        #expect(accepted.images[0].data == png.base64EncodedString())
        #expect(accepted.rejection == .unsupported)
        let full = acceptPromptFiles(
            [.success(PickedPromptFile(bytes: png, declaredMediaType: nil))],
            existing: Array(repeating: PromptImage(mediaType: "image/png", data: "YQ=="), count: promptImageLimit))
        #expect(full.images.count == promptImageLimit)
        #expect(full.rejection == .limit)
    }

    private func media(_ bytes: Data, declared: String?) -> String? {
        guard
            case .image(let image) = classifyPromptAttachment(
                bytes: bytes, declaredMediaType: declared, existingCount: 0)
        else { return nil }
        return image.mediaType
    }
}

private struct Probe: Decodable, Equatable {
    var text: String
    var mode: String
    var images: [PromptImage]?
}
