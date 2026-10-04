import Foundation

public enum InlineRun: Equatable, Sendable {
    case text(String)
    case code(String)
    case math(String)
}

public enum MarkdownBlock: Equatable, Sendable {
    case heading(level: Int, runs: [InlineRun])
    case paragraph(runs: [InlineRun])
    case list(ordered: Bool, items: [[InlineRun]])
    case quote(runs: [InlineRun])
    case table(header: [String], rows: [[String]])
    case code(language: String?, source: String)
    case math(source: String)
    case mermaid(source: String)
    case image(alt: String, url: String)
}

public enum ImageLoadDecision: Equatable, Sendable {
    case allowed(URL)
    case blocked
}

/// 只允许带主机名的 HTTPS。http、无方案、data 和脚本地址一律不加载。
public func imageLoadDecision(_ raw: String) -> ImageLoadDecision {
    let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    guard let components = URLComponents(string: trimmed), components.scheme?.lowercased() == "https",
        let host = components.host, !host.isEmpty, let url = components.url
    else { return .blocked }
    return .allowed(url)
}

/// 在代码围栏外的空行处切开，再把相邻块拼到大约 `targetCharacters`。短文不切。
public func splitMarkdownForLazyLayout(_ markdown: String, targetCharacters: Int = 320) -> [String] {
    let trimmed = markdown.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty { return [] }
    if trimmed.count <= targetCharacters { return [trimmed] }
    var blocks: [String] = []
    var current = ""
    var fence: String?
    for line in markdown.split(omittingEmptySubsequences: false, whereSeparator: \.isNewline) {
        let text = String(line)
        let marker = fenceMarker(text)
        if let marker {
            if fence == nil {
                fence = marker
            } else if fence == marker {
                fence = nil
            }
        }
        if text.trimmingCharacters(in: .whitespaces).isEmpty, fence == nil {
            if let piece = nonBlank(current) { blocks.append(piece) }
            current = ""
        } else {
            if !current.isEmpty { current.append("\n") }
            current.append(text)
        }
    }
    if let piece = nonBlank(current) { blocks.append(piece) }
    if blocks.count <= 1 { return [trimmed] }
    var chunks: [String] = []
    var chunk = ""
    for block in blocks {
        let separator = chunk.isEmpty ? 0 : 2
        if !chunk.isEmpty, chunk.count + separator + block.count > targetCharacters {
            chunks.append(chunk)
            chunk = ""
        }
        if !chunk.isEmpty { chunk.append("\n\n") }
        chunk.append(block)
    }
    if !chunk.isEmpty { chunks.append(chunk) }
    return chunks.isEmpty ? [trimmed] : chunks
}

public func parseMarkdown(_ markdown: String) -> [MarkdownBlock] {
    let lines = markdown.split(omittingEmptySubsequences: false, whereSeparator: \.isNewline).map(String.init)
    var blocks: [MarkdownBlock] = []
    var index = 0
    while index < lines.count {
        let line = lines[index]
        if line.trimmingCharacters(in: .whitespaces).isEmpty {
            index += 1
            continue
        }
        if let marker = fenceMarker(line) {
            let language = fenceLanguage(line, marker: marker)
            index += 1
            var body: [String] = []
            while index < lines.count {
                if fenceMarker(lines[index]) == marker {
                    index += 1
                    break
                }
                body.append(lines[index])
                index += 1
            }
            let source = body.joined(separator: "\n")
            if isMermaidLanguage(language) {
                blocks.append(.mermaid(source: source))
            } else if isMathLanguage(language) {
                blocks.append(.math(source: source))
            } else {
                blocks.append(.code(language: language, source: source))
            }
            continue
        }
        if isDisplayMathOpen(line) {
            if let close = mathClose(line) {
                blocks.append(.math(source: close))
                index += 1
                continue
            }
            index += 1
            var body: [String] = []
            while index < lines.count, !isDisplayMathOpen(lines[index]) {
                body.append(lines[index])
                index += 1
            }
            if index < lines.count { index += 1 }
            blocks.append(.math(source: body.joined(separator: "\n")))
            continue
        }
        if let image = imageLine(line) {
            blocks.append(.image(alt: image.alt, url: image.url))
            index += 1
            continue
        }
        if let heading = headingLine(line) {
            blocks.append(.heading(level: heading.level, runs: inlineRuns(heading.text)))
            index += 1
            continue
        }
        if line.trimmingCharacters(in: .whitespaces).hasPrefix(">") {
            var quoted: [String] = []
            while index < lines.count, lines[index].trimmingCharacters(in: .whitespaces).hasPrefix(">") {
                quoted.append(quoteText(lines[index]))
                index += 1
            }
            blocks.append(.quote(runs: inlineRuns(quoted.joined(separator: "\n"))))
            continue
        }
        if index + 1 < lines.count, isTableRow(line), isTableSeparator(lines[index + 1]) {
            let header = tableCells(line)
            index += 2
            var rows: [[String]] = []
            while index < lines.count, isTableRow(lines[index]) {
                rows.append(tableCells(lines[index]))
                index += 1
            }
            blocks.append(.table(header: header, rows: rows))
            continue
        }
        if let item = listItem(line) {
            let ordered = item.ordered
            var items: [[InlineRun]] = []
            while index < lines.count, let next = listItem(lines[index]), next.ordered == ordered {
                items.append(inlineRuns(next.text))
                index += 1
            }
            blocks.append(.list(ordered: ordered, items: items))
            continue
        }
        var paragraph: [String] = []
        while index < lines.count {
            let next = lines[index]
            if next.trimmingCharacters(in: .whitespaces).isEmpty || fenceMarker(next) != nil || headingLine(next) != nil
                || next.trimmingCharacters(in: .whitespaces).hasPrefix(">") || listItem(next) != nil
                || imageLine(next) != nil || isDisplayMathOpen(next)
            {
                break
            }
            paragraph.append(next)
            index += 1
        }
        if !paragraph.isEmpty {
            blocks.append(.paragraph(runs: inlineRuns(paragraph.joined(separator: "\n"))))
        }
    }
    return blocks
}

public func inlineRuns(_ text: String) -> [InlineRun] {
    var runs: [InlineRun] = []
    var buffer = ""
    var index = text.startIndex
    func flush() {
        if !buffer.isEmpty {
            runs.append(.text(buffer))
            buffer = ""
        }
    }
    while index < text.endIndex {
        let character = text[index]
        if character == "`" {
            let next = text.index(after: index)
            if next < text.endIndex, let end = text[next...].firstIndex(of: "`"), end > next {
                flush()
                runs.append(.code(String(text[next..<end])))
                index = text.index(after: end)
                continue
            }
        }
        if character == "$", text.index(after: index) < text.endIndex, text[text.index(after: index)] != "$" {
            let next = text.index(after: index)
            if let end = text[next...].firstIndex(of: "$"), end > next {
                flush()
                runs.append(.math(String(text[next..<end])))
                index = text.index(after: end)
                continue
            }
        }
        buffer.append(character)
        index = text.index(after: index)
    }
    flush()
    return runs.isEmpty ? [.text(text)] : runs
}

private func fenceMarker(_ line: String) -> String? {
    let trimmed = line.trimmingCharacters(in: .whitespaces)
    if trimmed.hasPrefix("```") { return "```" }
    if trimmed.hasPrefix("~~~") { return "~~~" }
    return nil
}

private func fenceLanguage(_ line: String, marker: String) -> String? {
    let trimmed = line.trimmingCharacters(in: .whitespaces)
    let language = trimmed.dropFirst(marker.count).trimmingCharacters(in: .whitespacesAndNewlines)
    return language.isEmpty ? nil : language
}

private func isMermaidLanguage(_ language: String?) -> Bool {
    guard let language else { return false }
    return language.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().hasPrefix("mermaid")
}

private func isMathLanguage(_ language: String?) -> Bool {
    switch language?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
    case "math", "latex", "tex", "katex": true
    default: false
    }
}

private func isDisplayMathOpen(_ line: String) -> Bool {
    let trimmed = line.trimmingCharacters(in: .whitespaces)
    return trimmed == "$$" || trimmed.hasPrefix("$$") && trimmed.hasSuffix("$$") && trimmed.count > 4
}

private func mathClose(_ line: String) -> String? {
    let trimmed = line.trimmingCharacters(in: .whitespaces)
    guard trimmed.hasPrefix("$$"), trimmed.hasSuffix("$$"), trimmed.count > 4 else { return nil }
    return String(trimmed.dropFirst(2).dropLast(2)).trimmingCharacters(in: .whitespacesAndNewlines)
}

private func imageLine(_ line: String) -> (alt: String, url: String)? {
    let trimmed = line.trimmingCharacters(in: .whitespaces)
    guard trimmed.hasPrefix("!["), let altEnd = trimmed.firstIndex(of: "]"),
        trimmed.index(after: altEnd) < trimmed.endIndex,
        trimmed[trimmed.index(after: altEnd)] == "(", trimmed.hasSuffix(")")
    else { return nil }
    let alt = String(trimmed[trimmed.index(trimmed.startIndex, offsetBy: 2)..<altEnd])
    let urlStart = trimmed.index(altEnd, offsetBy: 2)
    let url = String(trimmed[urlStart..<trimmed.index(before: trimmed.endIndex)])
    guard !url.isEmpty else { return nil }
    return (alt, url)
}

private func headingLine(_ line: String) -> (level: Int, text: String)? {
    let trimmed = line.trimmingCharacters(in: .whitespaces)
    var level = 0
    for character in trimmed {
        if character == "#" {
            level += 1
        } else {
            break
        }
    }
    guard level >= 1, level <= 6, trimmed.count > level,
        trimmed[trimmed.index(trimmed.startIndex, offsetBy: level)] == " "
    else { return nil }
    let text = trimmed.dropFirst(level + 1).trimmingCharacters(in: .whitespaces)
    return text.isEmpty ? nil : (level, String(text))
}

private func quoteText(_ line: String) -> String {
    var text = line.trimmingCharacters(in: .whitespaces)
    if text.hasPrefix(">") { text.removeFirst() }
    if text.hasPrefix(" ") { text.removeFirst() }
    return text
}

private func isTableRow(_ line: String) -> Bool {
    line.contains("|")
}

private func isTableSeparator(_ line: String) -> Bool {
    let cells = tableCells(line)
    guard !cells.isEmpty else { return false }
    return cells.allSatisfy { cell in
        let trimmed = cell.trimmingCharacters(in: .whitespaces)
        return trimmed.contains("-") && trimmed.allSatisfy { $0 == "-" || $0 == ":" || $0 == " " }
    }
}

private func tableCells(_ line: String) -> [String] {
    var text = line.trimmingCharacters(in: .whitespaces)
    if text.hasPrefix("|") { text.removeFirst() }
    if text.hasSuffix("|") { text.removeLast() }
    return text.split(separator: "|", omittingEmptySubsequences: false).map {
        $0.trimmingCharacters(in: .whitespaces)
    }
}

private func listItem(_ line: String) -> (ordered: Bool, text: String)? {
    let trimmed = line.trimmingCharacters(in: .whitespaces)
    if trimmed.hasPrefix("- ") || trimmed.hasPrefix("* ") || trimmed.hasPrefix("+ ") {
        return (false, String(trimmed.dropFirst(2)))
    }
    var index = trimmed.startIndex
    while index < trimmed.endIndex, trimmed[index].isNumber { index = trimmed.index(after: index) }
    guard index > trimmed.startIndex, index < trimmed.endIndex, trimmed[index] == ".",
        trimmed.index(after: index) < trimmed.endIndex, trimmed[trimmed.index(after: index)] == " "
    else { return nil }
    return (true, String(trimmed[trimmed.index(index, offsetBy: 2)...]))
}

private func nonBlank(_ text: String) -> String? {
    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed.isEmpty ? nil : trimmed
}
