import UIKit
import UniformTypeIdentifiers

/// Share sheet host. Writes one inbox record, then opens the app. No network and no Keychain.
final class ShareViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        Task { await accept() }
    }

    private func accept() async {
        let items = extensionContext?.inputItems.compactMap { $0 as? NSExtensionItem } ?? []
        let providers = items.flatMap { $0.attachments ?? [] }
        do {
            guard let store = ShareGroupStore.live() else { throw ShareInboxError.unreadable }
            let record = try await ShareExtensionIntake.record(from: providers, store: store)
            guard let url = ShareInbox.openURL(id: record.id) else { throw ShareInboxError.unsafePath }
            _ = await open(url)
            extensionContext?.completeRequest(returningItems: nil)
        } catch {
            extensionContext?.cancelRequest(withError: error)
        }
    }

    private func open(_ url: URL) async -> Bool {
        await withCheckedContinuation { continuation in
            extensionContext?.open(url) { continuation.resume(returning: $0) }
        }
    }
}

enum ShareExtensionIntake {
    static func record(from providers: [NSItemProvider], store: ShareGroupStore, now: Date = Date()) async throws
        -> ShareInboxRecord
    {
        if let text = try await firstText(providers) {
            let record = try ShareInbox.textRecord(text: text, now: now).get()
            try store.writeRecord(record)
            return record
        }
        if let image = try await firstImage(providers) {
            let id = ShareInbox.makeID()
            let record = try ShareInbox.imageRecord(bytes: image.bytes, text: image.caption, id: id, now: now).get()
            try store.writeRecord(record, image: image.bytes)
            return record
        }
        throw ShareInboxError.empty
    }

    private static func firstText(_ providers: [NSItemProvider]) async throws -> String? {
        for provider in providers where provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
            let item = try await provider.loadItem(forTypeIdentifier: UTType.plainText.identifier)
            if let text = item as? String { return text }
            if let data = item as? Data, let text = String(data: data, encoding: .utf8) { return text }
        }
        return nil
    }

    private static func firstImage(_ providers: [NSItemProvider]) async throws -> (bytes: Data, caption: String)? {
        for provider in providers where provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
            let item = try await provider.loadItem(forTypeIdentifier: UTType.image.identifier)
            let bytes: Data?
            if let url = item as? URL {
                bytes = try Data(contentsOf: url)
            } else if let image = item as? UIImage {
                bytes = image.pngData()
            } else {
                bytes = item as? Data
            }
            guard let bytes else { continue }
            guard bytes.count <= ShareAppGroup.imageByteLimit else { throw ShareInboxError.tooLarge }
            return (bytes, "")
        }
        return nil
    }
}

extension NSItemProvider {
    fileprivate func loadItem(forTypeIdentifier identifier: String) async throws -> NSSecureCoding? {
        try await withCheckedThrowingContinuation { continuation in
            loadItem(forTypeIdentifier: identifier, options: nil) { item, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: item)
                }
            }
        }
    }
}
