import SwiftUI

public enum DLFont {
    public static let title = Font.title3
    public static let headline = Font.headline
    public static let body = Font.body
    public static let meta = Font.subheadline
    public static let caption = Font.caption

    public static func mono(_ font: Font) -> Font {
        font.monospaced()
    }
}
