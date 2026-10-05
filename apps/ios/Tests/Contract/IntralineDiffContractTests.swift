import DLCore
import Foundation
import Testing

struct IntralineDiffContractTests {
    @Test func sharedCasesMatchAndroid() throws {
        let file = try JSONDecoder().decode(IntralineFile.self, from: Data(contentsOf: casesURL()))
        for item in file.tokens {
            #expect(intralinePieces(item.text, intralineTokens(item.text)) == item.pieces)
        }
        for item in file.emphasis {
            var budget = item.budget ?? intralineBudgetCells
            let emphasis = intralineEmphasis(item.old, item.new, budget: &budget)
            #expect(intralinePieces(item.old, emphasis.old) == item.oldPieces)
            #expect(intralinePieces(item.new, emphasis.new) == item.newPieces)
        }
        for item in file.pairs {
            var budget = intralineBudgetCells
            let rows = item.rows.map { DiffLine(kind: DiffLineKind(rawValue: $0.kind) ?? .context, text: $0.text) }
            let emphasized = withIntralineEmphasis(rows, budget: &budget)
            #expect(emphasized.count == item.rows.count)
            for (row, expected) in zip(emphasized, item.rows) {
                #expect(intralinePieces(row.text, row.emphasis) == expected.pieces)
            }
        }
    }

    private func casesURL() -> URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("testdata/intraline-diff-cases.json")
    }
}

private struct IntralineFile: Decodable {
    var tokens: [TokenCase]
    var emphasis: [EmphasisCase]
    var pairs: [PairCase]
}

private struct TokenCase: Decodable {
    var name: String
    var text: String
    var pieces: [String]
}

private struct EmphasisCase: Decodable {
    var name: String
    var old: String
    var new: String
    var budget: Int?
    var oldPieces: [String]
    var newPieces: [String]
}

private struct PairCase: Decodable {
    var name: String
    var rows: [PairRow]
}

private struct PairRow: Decodable {
    var kind: String
    var text: String
    var pieces: [String]
}
