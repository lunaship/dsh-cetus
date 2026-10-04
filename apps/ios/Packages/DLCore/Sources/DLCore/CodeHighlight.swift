import Foundation

public enum CodeTokenKind: Equatable, Sendable {
    case plain
    case keyword
    case string
    case comment
    case number
    case function
}

public struct CodeToken: Equatable, Sendable {
    public var kind: CodeTokenKind
    public var text: String

    public init(kind: CodeTokenKind, text: String) {
        self.kind = kind
        self.text = text
    }
}

/// 轻量分词。关键字表按 Android 的够用集合重写；比较忽略大小写，原文保留。
public func highlightCode(_ code: String, language: String?) -> [CodeToken] {
    let language = normalizeLanguage(language)
    let keywords = keywordSets[language] ?? []
    var tokens: [CodeToken] = []
    var index = code.startIndex
    func emit(_ kind: CodeTokenKind, _ end: String.Index) {
        guard end > index else { return }
        tokens.append(CodeToken(kind: kind, text: String(code[index..<end])))
        index = end
    }
    while index < code.endIndex {
        if isLineComment(code, at: index, language: language) {
            let end = code[index...].firstIndex(of: "\n") ?? code.endIndex
            emit(.comment, end)
            continue
        }
        if code[index...].hasPrefix("/*") {
            let end = code[code.index(index, offsetBy: 2)...].range(of: "*/").map { $0.upperBound } ?? code.endIndex
            emit(.comment, end)
            continue
        }
        let character = code[index]
        if character == "\"" || character == "'" || character == "`" {
            var cursor = code.index(after: index)
            while cursor < code.endIndex {
                if code[cursor] == "\\" {
                    cursor = code.index(cursor, offsetBy: 2, limitedBy: code.endIndex) ?? code.endIndex
                    continue
                }
                if code[cursor] == character {
                    cursor = code.index(after: cursor)
                    break
                }
                cursor = code.index(after: cursor)
            }
            emit(.string, cursor)
            continue
        }
        if character.isNumber || (character == "." && nextIsNumber(code, index)) {
            var cursor = code.index(after: index)
            let hex = code[index...].hasPrefix("0x") || code[index...].hasPrefix("0X")
            if hex { cursor = code.index(index, offsetBy: 2, limitedBy: code.endIndex) ?? code.endIndex }
            while cursor < code.endIndex {
                let next = code[cursor]
                if next.isNumber || next == "_" || (next == "." && !hex) || (hex && next.isHexDigit) {
                    cursor = code.index(after: cursor)
                } else {
                    break
                }
            }
            emit(.number, cursor)
            continue
        }
        if character.isLetter || character == "_" {
            var cursor = code.index(after: index)
            while cursor < code.endIndex, code[cursor].isLetter || code[cursor].isNumber || code[cursor] == "_" {
                cursor = code.index(after: cursor)
            }
            let word = String(code[index..<cursor])
            var look = cursor
            while look < code.endIndex, code[look].isWhitespace { look = code.index(after: look) }
            if keywords.contains(word.lowercased()) {
                emit(.keyword, cursor)
            } else if look < code.endIndex, code[look] == "(" {
                emit(.function, cursor)
            } else {
                emit(.plain, cursor)
            }
            continue
        }
        emit(.plain, code.index(after: index))
    }
    return tokens
}

private func normalizeLanguage(_ language: String?) -> String {
    switch language?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
    case "kotlin", "kt": "kt"
    case "java": "java"
    case "javascript", "js", "ts", "typescript", "jsx", "tsx": "js"
    case "python", "py": "py"
    case "go", "golang": "go"
    case "rust", "rs": "rust"
    case "c", "cpp", "c++", "cxx": "c"
    case "json": "json"
    case "bash", "sh", "shell", "zsh": "bash"
    case "sql": "sql"
    case "swift": "swift"
    default: "plain"
    }
}

private func isLineComment(_ code: String, at index: String.Index, language: String) -> Bool {
    let rest = code[index...]
    switch language {
    case "py", "bash": return rest.hasPrefix("#")
    case "sql": return rest.hasPrefix("--")
    default: return rest.hasPrefix("//")
    }
}

private func nextIsNumber(_ code: String, _ index: String.Index) -> Bool {
    let next = code.index(after: index)
    return next < code.endIndex && code[next].isNumber
}

private let keywordSets: [String: Set<String>] = [
    "kt": [
        "val", "var", "fun", "class", "object", "interface", "data", "sealed", "enum", "when", "if", "else", "for",
        "while", "return", "suspend", "import", "package", "private", "public", "internal", "override", "true", "false",
        "null",
    ],
    "java": [
        "public", "private", "protected", "class", "interface", "enum", "void", "static", "final", "if", "else", "for",
        "while", "return", "new", "import", "package", "try", "catch", "null", "true", "false",
    ],
    "js": [
        "const", "let", "var", "function", "return", "if", "else", "for", "while", "class", "import", "export", "from",
        "async", "await", "try", "catch", "new", "null", "true", "false", "undefined",
    ],
    "py": [
        "def", "class", "return", "if", "elif", "else", "for", "while", "import", "from", "try", "except", "with",
        "none", "true", "false", "and", "or", "not", "in", "is",
    ],
    "go": [
        "func", "var", "const", "type", "struct", "interface", "return", "if", "else", "for", "range", "import",
        "package", "nil", "true", "false", "defer", "go",
    ],
    "rust": [
        "fn", "let", "mut", "struct", "enum", "impl", "trait", "pub", "use", "if", "else", "for", "while", "return",
        "true", "false", "async", "await",
    ],
    "c": [
        "int", "void", "struct", "class", "if", "else", "for", "while", "return", "const", "static", "true", "false",
        "nullptr",
    ],
    "json": ["true", "false", "null"],
    "bash": ["if", "then", "else", "fi", "for", "in", "do", "done", "while", "echo", "export", "return"],
    "sql": ["select", "from", "where", "insert", "update", "delete", "create", "table", "join", "and", "or", "null"],
    "swift": [
        "func", "let", "var", "struct", "enum", "class", "protocol", "import", "return", "if", "else", "guard", "for",
        "while", "true", "false", "nil", "async", "await", "public", "private",
    ],
]
