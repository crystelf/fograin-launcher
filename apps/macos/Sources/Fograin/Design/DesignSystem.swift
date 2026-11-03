import AppKit
import FograinKit
import SwiftUI

// MARK: - Colors

/// Semantic colors from `design/tokens.json`, resolved against the effective
/// appearance so light and dark theme switches happen without rebuilding views.
enum FG {
    /// The resolved theme is pushed here by `AppModel`; colors are explicit
    /// token values rather than dynamic NSColors, so they cannot be resolved
    /// against a stale system appearance at launch or during a theme switch.
    static var theme: ResolvedTheme = .dark

    static var background: Color { color(FograinGenerated.Color.background) }
    static var surface: Color { color(FograinGenerated.Color.surface) }
    static var surfaceRaised: Color { color(FograinGenerated.Color.surfaceRaised) }
    static var sidebar: Color { color(FograinGenerated.Color.sidebarMac) }
    static var textPrimary: Color { color(FograinGenerated.Color.textPrimary) }
    static var textSecondary: Color { color(FograinGenerated.Color.textSecondary) }
    static var textTertiary: Color { color(FograinGenerated.Color.textTertiary) }
    static var separator: Color { color(FograinGenerated.Color.separator) }
    static var glass: Color { color(FograinGenerated.Color.glass) }
    static var glassStroke: Color { color(FograinGenerated.Color.glassStroke) }
    static var accent: Color { color(FograinGenerated.Color.accent) }
    static var onAccent: Color { color(FograinGenerated.Color.onAccent) }
    static var success: Color { color(FograinGenerated.Color.success) }
    static var danger: Color { color(FograinGenerated.Color.danger) }
    static var warning: Color { color(FograinGenerated.Color.warning) }

    private static func color(_ token: ColorToken) -> Color {
        let rgba = token.rgba(dark: theme == .dark)
        return Color(
            .sRGB,
            red: rgba.red,
            green: rgba.green,
            blue: rgba.blue,
            opacity: rgba.alpha
        )
    }
}



/// Colors that sit on top of the hero artwork rather than on a token surface.
enum FGImage {
    static let primary = Color.white.opacity(0.97)
    static let secondary = Color.white.opacity(0.74)
    static let chrome = Color.black.opacity(0.30)
    static let chromeStroke = Color.white.opacity(0.16)
}

extension ColorToken {
    var color: Color {
        Color(nsColor: nsColor)
    }

    var nsColor: NSColor {
        NSColor(name: nil) { appearance in
            let isDark = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            return rgba(dark: isDark).nsColor
        }
    }
}

extension RGBA {
    var color: Color {
        Color(nsColor: nsColor)
    }

    var nsColor: NSColor {
        NSColor(srgbRed: red, green: green, blue: blue, alpha: alpha)
    }
}

extension Instance.Palette {
    var baseColor: Color {
        switch self {
        case .forest: FograinGenerated.InstancePalette.forest.color
        case .copper: FograinGenerated.InstancePalette.copper.color
        case .slate: FograinGenerated.InstancePalette.slate.color
        case .violet: FograinGenerated.InstancePalette.violet.color
        case .clay: FograinGenerated.InstancePalette.clay.color
        case .lagoon: FograinGenerated.InstancePalette.lagoon.color
        case .sand: FograinGenerated.InstancePalette.sand.color
        case .moss: FograinGenerated.InstancePalette.moss.color
        case .ash: FograinGenerated.InstancePalette.ash.color
        }
    }

    var gradient: LinearGradient {
        LinearGradient(
            colors: [baseColor, baseColor.opacity(0.72)],
            startPoint: .top,
            endPoint: .bottom
        )
    }
}

extension LoaderKind {
    /// Tile color from `design/tokens.json` (`content.loaderPalette`).
    var tileColor: Color {
        switch self {
        case .fabric: FograinGenerated.LoaderPalette.fabric.color
        case .quilt: FograinGenerated.LoaderPalette.quilt.color
        case .forge: FograinGenerated.LoaderPalette.forge.color
        case .neoforge: FograinGenerated.LoaderPalette.neoforge.color
        case .vanilla: FograinGenerated.LoaderPalette.vanilla.color
        case .optifine: FograinGenerated.LoaderPalette.optifine.color
        }
    }

    var tileGlyph: String {
        switch self {
        case .fabric: "F"
        case .quilt: "Q"
        case .forge: "F"
        case .neoforge: "N"
        case .vanilla: "M"
        case .optifine: "O"
        }
    }

    /// Forge is the only loader the pen draws with an icon instead of a letter.
    var usesGlyphIcon: Bool {
        self == .forge
    }

    var onTile: Color {
        usesGlyphIcon ? .white : Color.black.opacity(0.78)
    }
}

// MARK: - Typography

enum FGFont {
    static let caption = Font.system(size: FograinGenerated.Typography.caption)
    static let captionMedium = Font.system(size: FograinGenerated.Typography.caption, weight: .medium)
    static let small = Font.system(size: FograinGenerated.Typography.small)
    static let smallMedium = Font.system(size: FograinGenerated.Typography.small, weight: .medium)
    static let body = Font.system(size: FograinGenerated.Typography.body)
    static let bodyMedium = Font.system(size: FograinGenerated.Typography.body, weight: .medium)
    static let subhead = Font.system(size: FograinGenerated.Typography.subhead)
    static let subheadBold = Font.system(size: FograinGenerated.Typography.subhead, weight: .bold)
    static let heading = Font.system(size: FograinGenerated.Typography.heading)
    static let headingBold = Font.system(size: FograinGenerated.Typography.heading, weight: .bold)
    static let title = Font.system(size: FograinGenerated.Typography.title, weight: .bold)
    static let page = Font.system(size: FograinGenerated.Typography.page, weight: .bold)
    static let hero = Font.system(size: FograinGenerated.Typography.hero, weight: .bold)
}

// MARK: - Metrics

enum FGSpacing {
    static let xxs = FograinGenerated.Spacing.s1
    static let xs = FograinGenerated.Spacing.s2
    static let sm = FograinGenerated.Spacing.s3
    static let md = FograinGenerated.Spacing.s4
    static let ml = FograinGenerated.Spacing.s5
    static let lg = FograinGenerated.Spacing.s6
    static let xl = FograinGenerated.Spacing.s7
    static let xxl = FograinGenerated.Spacing.s8
    static let section = FograinGenerated.Spacing.s9
    static let page = FograinGenerated.Spacing.s10
    static let hero = FograinGenerated.Spacing.s11
}

enum FGRadius {
    static let window = FograinGenerated.Radius.window
    static let control = FograinGenerated.Radius.control
    static let pill = FograinGenerated.Radius.pill
    static let card = FograinGenerated.Radius.card
    static let icon = FograinGenerated.Radius.icon
}

/// Measurements copied from the pen window so the shell keeps its proportions.
enum FGLayout {
    static let sidebarMin: CGFloat = 220
    static let sidebarIdeal: CGFloat = 240
    static let sidebarMax: CGFloat = 280

    static let contentPadding: CGFloat = 20
    static let blockGap: CGFloat = 22

    static let windowMinWidth: CGFloat = 1120
    static let windowMinHeight: CGFloat = 720
    static let windowDefaultWidth: CGFloat = 1360
    static let windowDefaultHeight: CGFloat = 820

    static let statusColumnWidth: CGFloat = 320
    static let previewColumnWidth: CGFloat = 330
    static let heroHeight: CGFloat = 330
}

// MARK: - Shared views

struct FGSeparator: View {
    var body: some View {
        Rectangle()
            .fill(FG.separator)
            .frame(height: 1)
    }
}

/// A title + content block separated by hairlines instead of a floating card.
struct FlatSection<Content: View>: View {
    let title: String?
    let detail: String?
    private let content: Content

    init(
        _ title: String? = nil,
        detail: String? = nil,
        @ViewBuilder content: () -> Content
    ) {
        self.title = title
        self.detail = detail
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: FGSpacing.lg) {
            if let title {
                HStack(alignment: .firstTextBaseline, spacing: FGSpacing.md) {
                    Text(title)
                        .font(FGFont.subheadBold)
                        .foregroundStyle(FG.textPrimary)
                    if let detail {
                        Text(detail)
                            .font(FGFont.caption)
                            .foregroundStyle(FG.textTertiary)
                    }
                    Spacer(minLength: 0)
                }
            }
            content
        }
    }
}

enum AppImage {
    /// Looks the bitmap up by file URL. `Image("name", bundle:)` does not find
    /// plain PNGs in a SwiftPM resource bundle reliably.
    static func nsImage(_ name: String) -> NSImage? {
        guard let url = Bundle.module.url(forResource: name, withExtension: "png") else {
            return nil
        }
        return NSImage(contentsOf: url)
    }
}

enum AppAssets {
    static var icon: NSImage? {
        AppImage.nsImage("app-icon")
    }
}


// MARK: - Native capsules

extension View {
    /// macOS 26/27 use the native Liquid Glass capsule buttons; macOS 14/15 fall
    /// back to bordered capsule/circle controls. Both are platform controls, so
    /// the shell keeps the same layout across supported systems.
    @ViewBuilder
    func fgCapsuleButton(prominent: Bool = false, circular: Bool = false) -> some View {
        #if compiler(>=6.2)
        if #available(macOS 26.0, *),
           ProcessInfo.processInfo.environment["FOGRAIN_DISABLE_GLASS"] != "1" {
            if prominent {
                buttonStyle(.glassProminent)
                    .buttonBorderShape(circular ? .circle : .capsule)
            } else {
                buttonStyle(.glass)
                    .buttonBorderShape(circular ? .circle : .capsule)
            }
        } else if prominent {
            buttonStyle(.borderedProminent)
                .buttonBorderShape(circular ? .circle : .capsule)
        } else {
            buttonStyle(.bordered)
                .buttonBorderShape(circular ? .circle : .capsule)
        }
        #else
        if prominent {
            buttonStyle(.borderedProminent)
                .buttonBorderShape(circular ? .circle : .capsule)
        } else {
            buttonStyle(.bordered)
                .buttonBorderShape(circular ? .circle : .capsule)
        }
        #endif
    }
}
