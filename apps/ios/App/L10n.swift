import Foundation

enum L10n {
    static func string(_ key: String.LocalizationValue, fallback: String.LocalizationValue) -> String {
        String(localized: key, defaultValue: fallback)
    }
}
