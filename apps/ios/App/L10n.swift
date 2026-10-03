import Foundation

enum L10n {
    static func string(_ key: String, fallback: String) -> String {
        NSLocalizedString(key, tableName: nil, bundle: .main, value: fallback, comment: "")
    }
}
