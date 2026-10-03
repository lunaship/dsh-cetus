import SwiftUI
import UIKit

public enum DLColor {
    public static let label = Color(uiColor: .label)
    public static let secondaryLabel = Color(uiColor: .secondaryLabel)
    public static let tertiaryLabel = Color(uiColor: .tertiaryLabel)
    public static let background = Color(uiColor: .systemBackground)
    public static let groupedBackground = Color(uiColor: .systemGroupedBackground)
    public static let fill = Color(uiColor: .systemFill)
    public static let wait = Color(uiColor: .systemOrange)
    public static let ok = Color(uiColor: .systemGreen)
    public static let err = Color(uiColor: .systemRed)
    public static let accent = Color.accentColor
    /// App asset `BrandFill`. Dark appearance is the single provisional token `#4C66E6`.
    public static let brandFill = Color("BrandFill")
}

public enum DLUIKitColor {
    public static let label = UIColor.label
    public static let secondaryLabel = UIColor.secondaryLabel
    public static let tertiaryLabel = UIColor.tertiaryLabel
    public static let background = UIColor.systemBackground
    public static let groupedBackground = UIColor.systemGroupedBackground
    public static let fill = UIColor.systemFill
    public static let wait = UIColor.systemOrange
    public static let ok = UIColor.systemGreen
    public static let err = UIColor.systemRed
    public static var brandFill: UIColor { UIColor(named: "BrandFill") ?? .label }
}
