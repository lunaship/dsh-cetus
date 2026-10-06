import DLCore
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// 5.6 文件来源。系统 `UIDocumentPicker` 只负责交出安全作用域 URL；
/// 读到的字节交给 `classifyPromptAttachment`，不在这里裁剪。
struct DocumentFilePicker: UIViewControllerRepresentable {
    var onPick: ([URL]) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: promptDocumentTypes, asCopy: false)
        picker.allowsMultipleSelection = true
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onPick: onPick, dismiss: dismiss)
    }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        var onPick: ([URL]) -> Void
        var dismiss: DismissAction

        init(onPick: @escaping ([URL]) -> Void, dismiss: DismissAction) {
            self.onPick = onPick
            self.dismiss = dismiss
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            onPick(urls)
            dismiss()
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            dismiss()
        }
    }
}

/// 文件来源接受任意文件。非图片在分类时拒绝，不另造上传接口。
let promptDocumentTypes: [UTType] = [.item]

struct PickedPromptFile: Equatable, Sendable {
    var bytes: Data
    var declaredMediaType: String?
}

enum PromptFileReadFailure: Error, Equatable, Sendable {
    case unreadable
}

/// 在调用方已经开始的安全作用域里读文件。读失败不抛到界面。
func readPromptFile(at url: URL) -> Result<PickedPromptFile, PromptFileReadFailure> {
    guard let bytes = try? Data(contentsOf: url, options: [.mappedIfSafe]) else {
        return .failure(.unreadable)
    }
    return .success(PickedPromptFile(bytes: bytes, declaredMediaType: declaredPromptMediaType(for: url)))
}

func declaredPromptMediaType(for url: URL) -> String? {
    guard let type = UTType(filenameExtension: url.pathExtension) else { return nil }
    return type.preferredMIMEType
}

func promptFileRejection(_ failure: PromptFileReadFailure) -> PromptAttachmentRejection {
    switch failure {
    case .unreadable: .unreadable
    }
}

struct AcceptedPromptFiles: Equatable, Sendable {
    var images: [PromptImage]
    var rejection: PromptAttachmentRejection?
}

/// 按选择顺序接收。能发的图片留下，第一份失败作为提示；不裁剪。
func acceptPromptFiles(_ files: [Result<PickedPromptFile, PromptFileReadFailure>], existing: [PromptImage])
    -> AcceptedPromptFiles
{
    var images = existing
    var rejection: PromptAttachmentRejection?
    for file in files {
        switch file {
        case .failure(let failure):
            rejection = rejection ?? promptFileRejection(failure)
        case .success(let picked):
            switch classifyPromptAttachment(
                bytes: picked.bytes, declaredMediaType: picked.declaredMediaType, existingCount: images.count)
            {
            case .image(let image):
                images.append(image)
            case .rejected(let reason):
                rejection = rejection ?? reason
            }
        }
    }
    return AcceptedPromptFiles(images: images, rejection: rejection)
}
