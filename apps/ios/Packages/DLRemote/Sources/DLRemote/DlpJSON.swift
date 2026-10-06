import Foundation

/// Control-frame JSON value. Numbers keep their original literal so large integers are not rounded.
public enum DlpJSON: Sendable {
    case object([Field])
    case array([DlpJSON])
    case string(String)
    case number(String)
    case bool(Bool)
    case null

    public struct Field: Sendable {
        public var key: String
        public var value: DlpJSON

        public init(_ key: String, _ value: DlpJSON) {
            self.key = key
            self.value = value
        }
    }

    public var objectFields: [Field]? {
        guard case .object(let fields) = self else { return nil }
        return fields
    }

    public var string: String? {
        guard case .string(let value) = self else { return nil }
        return value
    }

    /// Exact integer only. Leading zeros, fractions, and exponents are not integers.
    public var int: Int? {
        guard case .number(let literal) = self, let value = Int(literal), String(value) == literal else { return nil }
        return value
    }

    public static func == (lhs: DlpJSON, rhs: DlpJSON) -> Bool {
        switch (lhs, rhs) {
        case (.object(let left), .object(let right)):
            guard left.count == right.count else { return false }
            return zip(left, right).allSatisfy { $0.key == $1.key && $0.value == $1.value }
        case (.array(let left), .array(let right)):
            guard left.count == right.count else { return false }
            return zip(left, right).allSatisfy { $0 == $1 }
        case (.string(let left), .string(let right)): return left == right
        case (.number(let left), .number(let right)): return left == right
        case (.bool(let left), .bool(let right)): return left == right
        case (.null, .null): return true
        default: return false
        }
    }

    var encoded: String {
        switch self {
        case .object(let fields):
            let body = fields.map { DlpJSON.string($0.key).encoded + ":" + $0.value.encoded }.joined(separator: ",")
            return "{" + body + "}"
        case .array(let values):
            return "[" + values.map { $0.encoded }.joined(separator: ",") + "]"
        case .string(let value):
            return Self.encodeString(value)
        case .number(let literal):
            return literal
        case .bool(let value):
            return value ? "true" : "false"
        case .null:
            return "null"
        }
    }

    private static func encodeString(_ value: String) -> String {
        var out = String(Unicode.Scalar(0x22)!)
        let quote = Unicode.Scalar(0x22)!
        let backslash = Unicode.Scalar(0x5C)!
        for scalar in value.unicodeScalars {
            switch scalar {
            case quote:
                out.unicodeScalars.append(backslash)
                out.unicodeScalars.append(quote)
            case backslash:
                out.unicodeScalars.append(backslash)
                out.unicodeScalars.append(backslash)
            case Unicode.Scalar(0x08)!:
                out += escaped("b")
            case Unicode.Scalar(0x0C)!:
                out += escaped("f")
            case Unicode.Scalar(0x0A)!:
                out += escaped("n")
            case Unicode.Scalar(0x0D)!:
                out += escaped("r")
            case Unicode.Scalar(0x09)!:
                out += escaped("t")
            case Unicode.Scalar(0x00)!...Unicode.Scalar(0x1F)!:
                out += escaped("u") + String(format: "%04x", scalar.value)
            default:
                out.unicodeScalars.append(scalar)
            }
        }
        out.unicodeScalars.append(quote)
        return out
    }

    private static func escaped(_ letter: String) -> String {
        String(Unicode.Scalar(0x5C)!) + letter
    }
}

/// One-pass JSON parser. Duplicate keys are rejected. Numbers stay as literals.
struct JSONValueParser {
    private let scalars: [Unicode.Scalar]
    private var index = 0

    init(_ text: String) {
        scalars = Array(text.unicodeScalars)
    }

    var isAtEnd: Bool { index >= scalars.count }

    mutating func parseValue() throws -> DlpJSON {
        skipWhitespace()
        guard let scalar = peek() else { throw JSONParseError.truncated }
        switch scalar {
        case "{": return .object(try parseObject())
        case "[": return .array(try parseArray())
        case quote: return .string(try parseString())
        case "t":
            try consumeLiteral("true")
            return .bool(true)
        case "f":
            try consumeLiteral("false")
            return .bool(false)
        case "n":
            try consumeLiteral("null")
            return .null
        default:
            return .number(try parseNumber())
        }
    }

    @discardableResult
    mutating func skipWhitespace() -> Bool {
        while let scalar = peek(), scalar == " " || scalar == newline || scalar == carriage || scalar == tab {
            index += 1
        }
        return true
    }

    private var quote: Unicode.Scalar { Unicode.Scalar(0x22)! }
    private var backslash: Unicode.Scalar { Unicode.Scalar(0x5C)! }
    private var newline: Unicode.Scalar { Unicode.Scalar(0x0A)! }
    private var carriage: Unicode.Scalar { Unicode.Scalar(0x0D)! }
    private var tab: Unicode.Scalar { Unicode.Scalar(0x09)! }

    private mutating func parseObject() throws -> [DlpJSON.Field] {
        try consume("{")
        skipWhitespace()
        var fields: [DlpJSON.Field] = []
        var seen = Set<String>()
        if peek() == "}" {
            index += 1
            return fields
        }
        while true {
            skipWhitespace()
            guard peek() == quote else { throw JSONParseError.invalid }
            let key = try parseString()
            if !seen.insert(key).inserted { throw JSONParseError.duplicateKey }
            skipWhitespace()
            try consume(":")
            let value = try parseValue()
            fields.append(DlpJSON.Field(key, value))
            skipWhitespace()
            if peek() == "," {
                index += 1
                continue
            }
            try consume("}")
            return fields
        }
    }

    private mutating func parseArray() throws -> [DlpJSON] {
        try consume("[")
        skipWhitespace()
        var values: [DlpJSON] = []
        if peek() == "]" {
            index += 1
            return values
        }
        while true {
            values.append(try parseValue())
            skipWhitespace()
            if peek() == "," {
                index += 1
                continue
            }
            try consume("]")
            return values
        }
    }

    private mutating func parseString() throws -> String {
        try consume(quote)
        var out = String.UnicodeScalarView()
        while let scalar = peek() {
            index += 1
            if scalar == quote { return String(out) }
            if scalar == backslash {
                guard let escaped = peek() else { throw JSONParseError.truncated }
                index += 1
                switch escaped {
                case quote:
                    out.append(quote)
                case backslash:
                    out.append(backslash)
                case "/":
                    out.append("/")
                case "b":
                    out.append(Unicode.Scalar(0x08))
                case "f":
                    out.append(Unicode.Scalar(0x0C))
                case "n":
                    out.append(newline)
                case "r":
                    out.append(carriage)
                case "t":
                    out.append(tab)
                case "u":
                    let unit = try parseHex4()
                    if (0xD800...0xDBFF).contains(unit) {
                        guard peek() == backslash else { throw JSONParseError.invalid }
                        index += 1
                        try consume("u")
                        let low = try parseHex4()
                        let combined = 0x10000 + ((Int(unit) - 0xD800) << 10) + (Int(low) - 0xDC00)
                        guard (0xDC00...0xDFFF).contains(low), let scalar = Unicode.Scalar(combined) else {
                            throw JSONParseError.invalid
                        }
                        out.append(scalar)
                    } else if (0xDC00...0xDFFF).contains(unit) {
                        throw JSONParseError.invalid
                    } else {
                        guard let scalar = Unicode.Scalar(unit) else { throw JSONParseError.invalid }
                        out.append(scalar)
                    }
                default:
                    throw JSONParseError.invalid
                }
            } else if scalar.value < 0x20 {
                throw JSONParseError.invalid
            } else {
                out.append(scalar)
            }
        }
        throw JSONParseError.truncated
    }

    private mutating func parseHex4() throws -> UInt32 {
        var value: UInt32 = 0
        for _ in 0..<4 {
            guard let scalar = peek() else { throw JSONParseError.truncated }
            index += 1
            let digit: UInt32
            switch scalar.value {
            case 48...57: digit = scalar.value - 48
            case 65...70: digit = scalar.value - 55
            case 97...102: digit = scalar.value - 87
            default: throw JSONParseError.invalid
            }
            value = (value << 4) | digit
        }
        return value
    }

    private mutating func parseNumber() throws -> String {
        let start = index
        if peek() == "-" { index += 1 }
        guard let first = peek(), ("0"..."9").contains(first) else { throw JSONParseError.invalid }
        if first == "0" {
            index += 1
        } else {
            while let scalar = peek(), ("0"..."9").contains(scalar) { index += 1 }
        }
        if peek() == "." {
            index += 1
            guard let digit = peek(), ("0"..."9").contains(digit) else { throw JSONParseError.invalid }
            while let scalar = peek(), ("0"..."9").contains(scalar) { index += 1 }
        }
        if peek() == "e" || peek() == "E" {
            index += 1
            if peek() == "+" || peek() == "-" { index += 1 }
            guard let digit = peek(), ("0"..."9").contains(digit) else { throw JSONParseError.invalid }
            while let scalar = peek(), ("0"..."9").contains(scalar) { index += 1 }
        }
        return String(String.UnicodeScalarView(scalars[start..<index]))
    }

    private mutating func consumeLiteral(_ literal: String) throws {
        for scalar in literal.unicodeScalars {
            try consume(scalar)
        }
    }

    private mutating func consume(_ expected: Unicode.Scalar) throws {
        guard peek() == expected else { throw JSONParseError.invalid }
        index += 1
    }

    private func peek() -> Unicode.Scalar? {
        index < scalars.count ? scalars[index] : nil
    }
}

enum JSONParseError: Error {
    case invalid
    case truncated
    case duplicateKey
}
