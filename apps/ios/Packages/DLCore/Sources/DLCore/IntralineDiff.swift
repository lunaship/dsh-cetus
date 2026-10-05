import Foundation

/// 行内变化区间，按 UTF-16 偏移，`end` 不含在内。与 Android `IntRange` 的首尾闭区间对齐时，`end` 比 `last` 大 1。
public struct TextRange: Equatable, Sendable {
    public var start: Int
    public var end: Int

    public init(start: Int, end: Int) {
        self.start = start
        self.end = end
    }
}

public let intralineMaxChangedRatio = 0.6
public let intralineMaxCells = 40_000
public let intralineBudgetCells = 2_000_000
public let intralinePairWindow = 8

public enum DiffLineKind: String, Equatable, Sendable {
    case hunk
    case context
    case add
    case delete
    case fold
}

public struct DiffLine: Equatable, Sendable {
    public var kind: DiffLineKind
    public var text: String
    public var emphasis: [TextRange]

    public init(kind: DiffLineKind, text: String, emphasis: [TextRange] = []) {
        self.kind = kind
        self.text = text
        self.emphasis = emphasis
    }
}

public func intralineTokens(_ text: String) -> [TextRange] {
    var out: [TextRange] = []
    let scalars = Array(text.unicodeScalars)
    var index = 0
    var offset = 0
    while index < scalars.count {
        let start = offset
        let scalar = scalars[index]
        offset += scalar.utf16.count
        index += 1
        if scalar.properties.isWhitespace {
            while index < scalars.count, scalars[index].properties.isWhitespace {
                offset += scalars[index].utf16.count
                index += 1
            }
        } else if isWordScalar(scalar) {
            while index < scalars.count, isWordScalar(scalars[index]) {
                offset += scalars[index].utf16.count
                index += 1
            }
        }
        out.append(TextRange(start: start, end: offset))
    }
    return out
}

public func intralineEmphasis(_ old: String, _ new: String, budget: inout Int) -> (old: [TextRange], new: [TextRange]) {
    let none = (old: [TextRange](), new: [TextRange]())
    if old == new { return none }
    let left = intralineTokens(old)
    let right = intralineTokens(new)
    func tokenA(_ index: Int) -> String { slice(old, left[index]) }
    func tokenB(_ index: Int) -> String { slice(new, right[index]) }

    var head = 0
    while head < left.count, head < right.count, tokenA(head) == tokenB(head) { head += 1 }
    var tail = 0
    while tail < left.count - head, tail < right.count - head,
        tokenA(left.count - 1 - tail) == tokenB(right.count - 1 - tail)
    {
        tail += 1
    }
    let rowCount = left.count - head - tail
    let columnCount = right.count - head - tail
    let cells = rowCount * columnCount
    if cells > intralineMaxCells || cells > budget { return none }
    budget -= cells

    var matchedA = Array(repeating: false, count: left.count)
    var matchedB = Array(repeating: false, count: right.count)
    for index in 0..<head {
        matchedA[index] = true
        matchedB[index] = true
    }
    if tail > 0 {
        for index in (left.count - tail)..<left.count { matchedA[index] = true }
        for index in (right.count - tail)..<right.count { matchedB[index] = true }
    }
    if rowCount > 0, columnCount > 0 {
        var lengths = Array(repeating: Array(repeating: 0, count: columnCount + 1), count: rowCount + 1)
        var row = rowCount - 1
        while row >= 0 {
            var column = columnCount - 1
            while column >= 0 {
                if tokenA(head + row) == tokenB(head + column) {
                    lengths[row][column] = lengths[row + 1][column + 1] + 1
                } else {
                    lengths[row][column] = max(lengths[row + 1][column], lengths[row][column + 1])
                }
                column -= 1
            }
            row -= 1
        }
        var i = 0
        var j = 0
        while i < rowCount, j < columnCount {
            if tokenA(head + i) == tokenB(head + j) {
                matchedA[head + i] = true
                matchedB[head + j] = true
                i += 1
                j += 1
            } else if lengths[i + 1][j] >= lengths[i][j + 1] {
                i += 1
            } else {
                j += 1
            }
        }
    }
    let oldRanges = changedRanges(old, tokens: left, matched: matchedA)
    let newRanges = changedRanges(new, tokens: right, matched: matchedB)
    if tooMuchChanged(old, oldRanges) || tooMuchChanged(new, newRanges) { return none }
    return (oldRanges, newRanges)
}

public func withIntralineEmphasis(_ rows: [DiffLine], budget: inout Int) -> [DiffLine] {
    var out = rows
    var index = 0
    while index < out.count {
        if out[index].kind != .delete {
            index += 1
            continue
        }
        let deleteStart = index
        while index < out.count, out[index].kind == .delete { index += 1 }
        let addStart = index
        while index < out.count, out[index].kind == .add { index += 1 }
        let addEnd = index
        var nextAdd = addStart
        for deleteIndex in deleteStart..<addStart {
            if nextAdd >= addEnd { break }
            let deleted = out[deleteIndex]
            let windowEnd = min(addEnd, nextAdd + intralinePairWindow)
            for addIndex in nextAdd..<windowEnd {
                let added = out[addIndex]
                let emphasis = intralineEmphasis(deleted.text, added.text, budget: &budget)
                if emphasis.old.isEmpty, emphasis.new.isEmpty { continue }
                out[deleteIndex].emphasis = emphasis.old
                out[addIndex].emphasis = emphasis.new
                nextAdd = addIndex + 1
                break
            }
        }
    }
    return out
}

public func intralinePieces(_ text: String, _ ranges: [TextRange]) -> [String] {
    ranges.map { slice(text, $0) }
}

private func isWordScalar(_ scalar: Unicode.Scalar) -> Bool {
    (scalar.properties.isAlphabetic || scalar.properties.numericType != nil || scalar == "_")
        && !scalar.properties.isIdeographic
}

private func slice(_ text: String, _ range: TextRange) -> String {
    let start = String.Index(utf16Offset: range.start, in: text)
    let end = String.Index(utf16Offset: range.end, in: text)
    return String(text[start..<end])
}

private func changedRanges(_ text: String, tokens: [TextRange], matched: [Bool]) -> [TextRange] {
    var out: [TextRange] = []
    for (index, token) in tokens.enumerated() where !matched[index] {
        if let last = out.last, gapIsBlank(text, from: last.end, to: token.start) {
            out[out.count - 1] = TextRange(start: last.start, end: token.end)
        } else {
            out.append(token)
        }
    }
    return out
}

private func gapIsBlank(_ text: String, from: Int, to: Int) -> Bool {
    guard from < to else { return true }
    let start = String.Index(utf16Offset: from, in: text)
    let end = String.Index(utf16Offset: to, in: text)
    return text[start..<end].unicodeScalars.allSatisfy(\.properties.isWhitespace)
}

private func tooMuchChanged(_ text: String, _ ranges: [TextRange]) -> Bool {
    let visible = text.unicodeScalars.filter { !$0.properties.isWhitespace }.count
    if visible == 0 { return false }
    let changed = ranges.reduce(0) { total, range in
        total + slice(text, range).unicodeScalars.filter { !$0.properties.isWhitespace }.count
    }
    return Double(changed) > Double(visible) * intralineMaxChangedRatio
}
