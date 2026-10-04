import CoreImage
import DLNet
import Foundation
import Vision

struct PairingPhotoDecoder {
    static func decode(_ data: Data) async -> Result<String, PhotoError> {
        await Task.detached(priority: .userInitiated) {
            var texts: [String] = []
            do {
                let request = VNDetectBarcodesRequest()
                request.symbologies = [.qr]
                // Data-backed Vision handler reads EXIF orientation; no photo-library permission needed.
                try VNImageRequestHandler(data: data).perform([request])
                texts = request.results?.compactMap(\.payloadStringValue) ?? []
            } catch {
                // Vision models can fail to load on simulators or devices without a usable ANE.
            }
            if texts.isEmpty { texts = coreImageTexts(data) }
            if let text = texts.first(where: { (try? PairingQRPayload($0)) != nil }) { return .success(text) }
            return .failure(texts.isEmpty ? .notFound : .invalid)
        }.value
    }

    /// CPU fallback, also exercised directly so tests cover it when Vision succeeds.
    static func coreImageTexts(_ data: Data) -> [String] {
        guard let image = CIImage(data: data, options: [.applyOrientationProperty: true]) else { return [] }
        let context = CIContext(options: [.useSoftwareRenderer: true])
        guard
            let detector = CIDetector(
                ofType: CIDetectorTypeQRCode, context: context,
                options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
        else { return [] }
        return detector.features(in: image).compactMap { ($0 as? CIQRCodeFeature)?.messageString }
    }

    enum PhotoError: Error, Equatable, Sendable { case notFound, invalid }
}
