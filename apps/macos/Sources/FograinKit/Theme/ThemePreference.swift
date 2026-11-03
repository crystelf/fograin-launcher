import Foundation

/// How the launcher chooses its color theme (`appearance.theme` in launcher.toml).
public enum ThemePreference: String, CaseIterable, Codable, Identifiable, Sendable {
    case system
    case light
    case dark

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .system: "跟随系统"
        case .light: "浅色"
        case .dark: "深色"
        }
    }

    public var symbolName: String {
        switch self {
        case .system: "circle.lefthalf.filled"
        case .light: "sun.max"
        case .dark: "moon"
        }
    }

    /// The concrete theme, or `nil` when the system appearance decides.
    public var resolved: ResolvedTheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

/// The concrete appearance after resolving `.system`.
public enum ResolvedTheme: String, Codable, Sendable {
    case light
    case dark

    public var isDark: Bool { self == .dark }

    public var title: String {
        switch self {
        case .light: "浅色"
        case .dark: "深色"
        }
    }
}
