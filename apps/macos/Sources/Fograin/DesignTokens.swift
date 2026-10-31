import SwiftUI

/// Starter values from `design/tokens.json`.
/// The generated token step will replace this file; do not fork values per view.
enum FGToken {
    static let windowRadius: CGFloat = 26
    static let titlebarHeight: CGFloat = 38
    static let trafficLightInset = CGPoint(x: 18, y: 24)

    static let accent = Color(hex: 0x0A84FF)
    static let success = Color(hex: 0x30D158)
    static let danger = Color(hex: 0xFF453A)

    static let textPrimary = Color(hex: 0xF5F5F7)
    static let textSecondary = Color(hex: 0xEBEBF5).opacity(0.60)
    static let textTertiary = Color(hex: 0xEBEBF5).opacity(0.36)
    static let sidebar = Color(hex: 0x3A3A3F).opacity(0.50)
    static let content = Color(hex: 0x1F1F22)
    static let separator = Color.white.opacity(0.08)

    static let uiFont = Font.system(size: 13, weight: .regular, design: .default)
    static let titleFont = Font.system(size: 22, weight: .semibold, design: .default)
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}
