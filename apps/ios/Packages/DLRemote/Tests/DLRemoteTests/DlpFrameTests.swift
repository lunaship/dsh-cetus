import DLRemote
import Foundation
import Testing

struct DlpFrameTests {
    @Test func controlFramesRoundTripKnownMessages() {
        let hello = DlpWire.encodeControl([
            DlpJSON.Field(s([116]), .string(s([104, 101, 108, 108, 111]))),
            DlpJSON.Field(s([118]), .number(s([49]))),
            DlpJSON.Field(s([99, 104]), .string(s([97, 98, 99]))),
            DlpJSON.Field(s([110, 111, 119]), .number(s([49, 55, 57, 48, 48, 48, 48, 48, 48, 48]))),
        ])
        let expected = s([
            123, 34, 116, 34, 58, 34, 104, 101, 108, 108, 111, 34, 44, 34, 118, 34, 58, 49, 44, 34, 99, 104, 34, 58, 34,
            97, 98, 99, 34, 44, 34, 110, 111, 119, 34, 58, 49, 55, 57, 48, 48, 48, 48, 48, 48, 48, 125,
        ])
        #expect(hello == expected)
        let parsed = DlpWire.parseControlFrame(hello)
        #expect(parsed?[s([116])]?.string == s([104, 101, 108, 108, 111]))
        #expect(parsed?[s([118])]?.int == 1)
        #expect(parsed?[s([110, 111, 119])]?.int == 1_790_000_000)
    }

    @Test func parserRejectsProtocolFailures() {
        let rejected = [
            s([]),
            s([91, 93]),
            s([110, 117, 108, 108]),
            s([49]),
            s([34,116,101,120,116,34]),
            s([123,34,116,34,58,34,97,34,44,34,116,34,58,34,98,34,125]),
            s([123,34,120,34,58,123,34,97,34,58,49,44,34,97,34,58,50,125,125]),
            s([123,34,111,107,34,58,116,114,117,101,125,32,123]),
            s([123,34,98,97,100,34,58,48,49,125]),
            s([123,34,111,112,101,110,34,58,125]),
            s([123,34,117,34,58,34,92,117,68,56,48,48,34,125]),
        ]
        for text in rejected {
            #expect(DlpWire.parseControlFrame(text) == nil)
        }
        let oversize =
            s([123, 34, 120, 34, 58, 34]) + String(repeating: s([97]), count: DlpWire.maxControlBytes) + s([34, 125])
        #expect(DlpWire.parseControlFrame(oversize) == nil)
        var badUTF8 = Data([0x7b, 0x22, 0x74, 0x22, 0x3a, 0x22])
        badUTF8.append(0xff)
        badUTF8.append(contentsOf: [0x22, 0x7d])
        #expect(DlpWire.parseControlFrame(badUTF8) == nil)
        let truncated = Data((s([123,34,116,34,58,34,112,105,110,103])).utf8)
        #expect(DlpWire.parseControlFrame(truncated) == nil)
    }

    @Test func duplicateKeysInsideStringsAreNotDuplicates() {
        let raw = s([
            123, 34, 97, 34, 58, 34, 123, 92, 34, 97, 92, 34, 58, 49, 125, 34, 44, 34, 98, 34, 58, 91, 49, 44, 123, 34,
            97, 34, 58, 116, 114, 117, 101, 125, 93, 125,
        ])
        let parsed = DlpWire.parseControlFrame(raw)
        #expect(parsed?[s([97])]?.string == s([123,34,97,34,58,49,125]))
        #expect(parsed?[s([98])] != nil)
    }

    @Test func safeCodeKeepsOnlyKnownAlphabet() {
        #expect(DlpWire.safeCode(s([66, 65, 68, 95, 77, 65, 67])) == s([66, 65, 68, 95, 77, 65, 67]))
        #expect(DlpWire.safeCode(s([98, 97, 100])) == s([85, 78, 75, 78, 79, 87, 78]))
        #expect(DlpWire.safeCode(String(repeating: s([65]), count: 33)) == s([85, 78, 75, 78, 79, 87, 78]))
        #expect(DlpWire.safeCode(nil) == s([85, 78, 75, 78, 79, 87, 78]))
        #expect(DlpWire.Reject.all.count == 10)
        #expect(DlpWire.Close.protocolError == 4000)
        #expect(DlpWire.Close.replaced == 4010)
        #expect(DlpWire.Close.agentRejected == 4007)
    }

    @Test func dataChunksStayInsideRelayLimits() {
        let payload = Data(repeating: 7, count: DlpWire.dataChunkBytes + 3)
        let chunks = DlpWire.chunkData(payload)
        #expect(chunks.count == 2)
        #expect(chunks[0].count == DlpWire.dataChunkBytes)
        #expect(chunks[1].count == 3)
        #expect(chunks.allSatisfy(DlpWire.isValidDataMessage))
        #expect(DlpWire.chunkData(Data()).isEmpty)
        let huge = Data(count: DlpWire.maxDataMessageBytes + 1)
        #expect(!DlpWire.isValidDataMessage(huge))
        #expect(DlpWire.isValidDataMessage(Data()))
    }

    @Test func randomAndTruncatedBytesDoNotCrash() {
        var generator = SplitMix64(state: 0xD1_F0_0001)
        for _ in 0..<400 {
            let count = Int(generator.next() % 96)
            var bytes = Data(count: count)
            for index in bytes.indices {
                bytes[index] = UInt8(truncatingIfNeeded: generator.next())
            }
            _ = DlpWire.parseControlFrame(bytes)
            if count > 0 {
                _ = DlpWire.parseControlFrame(bytes.prefix(count / 2))
            }
            _ = DlpWire.isValidDataMessage(bytes)
            _ = DlpWire.safeCode(String(data: bytes, encoding: .utf8))
        }
        let duplicate = Data((s([123,34,107,34,58,49,44,34,107,34,58,50,125])).utf8)
        #expect(DlpWire.parseControlFrame(duplicate) == nil)
        #expect(!DlpCrypto.safeEqual(Data([1, 2, 3]), Data([1, 2, 4])))
        #expect(DlpCrypto.safeEqual(Data([1, 2, 3]), Data([1, 2, 3])))
        #expect(!DlpCrypto.verify(
            publicKey: Data(repeating: 9, count: 32),
            transcript: Data([1]),
            signature: Data(repeating: 8, count: 64)
        ))
    }
}

private func s(_ codes: [UInt32]) -> String {
    String(String.UnicodeScalarView(codes.map { Unicode.Scalar($0)! }))
}

private struct SplitMix64 {
    var state: UInt64
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}
