import DLNet
import Foundation
import Testing

@testable import DeepLinks

struct PairingPhotoDecoderTests {
    private func fixture(_ name: String) throws -> Data {
        let directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("Fixtures")
        return try Data(contentsOf: directory.appendingPathComponent("\(name).png"))
    }

    @Test func recognizesDeepLinksAndRejectsOtherQRCodes() async throws {
        let valid = await PairingPhotoDecoder.decode(try fixture("pairing-valid"))
        #expect(valid == .success(PairingFixtures.qr))
        let unrelated = await PairingPhotoDecoder.decode(try fixture("pairing-unrelated"))
        #expect(unrelated == .failure(.invalid))
    }

    @Test func unreadableImageDoesNotProduceAPairingRequest() async {
        #expect(await PairingPhotoDecoder.decode(Data([0, 1, 2])) == .failure(.notFound))
    }

    @Test func cpuFallbackRecognizesQRCodesWithoutVision() throws {
        #expect(PairingPhotoDecoder.coreImageTexts(try fixture("pairing-valid")) == [PairingFixtures.qr])
        let unrelated = PairingPhotoDecoder.coreImageTexts(try fixture("pairing-unrelated"))
        #expect(!unrelated.isEmpty)
        #expect(unrelated.allSatisfy { (try? PairingQRPayload($0)) == nil })
        #expect(PairingPhotoDecoder.coreImageTexts(Data([0, 1, 2])).isEmpty)
    }
}
