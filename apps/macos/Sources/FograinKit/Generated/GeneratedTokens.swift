// Generated from design/tokens.json. Do not edit by hand.
// Regenerate with `python3 scripts/generate_tokens.py`.

import CoreGraphics

/// Straight sRGB components. The Swift UI turns these into dynamic NSColors.
public struct RGBA: Sendable, Equatable {
    public let red: Double
    public let green: Double
    public let blue: Double
    public let alpha: Double

    public init(red: Double, green: Double, blue: Double, alpha: Double) {
        self.red = red
        self.green = green
        self.blue = blue
        self.alpha = alpha
    }
}

public struct ColorToken: Sendable, Equatable {
    public let light: RGBA
    public let dark: RGBA

    public init(light: RGBA, dark: RGBA) {
        self.light = light
        self.dark = dark
    }

    public func rgba(dark isDark: Bool) -> RGBA {
        isDark ? dark : light
    }
}

public enum FograinGenerated {
    public enum Color {
        public static let background = ColorToken(
            light: RGBA(red: 0.960784, green: 0.960784, blue: 0.968627, alpha: 1.000000),
            dark: RGBA(red: 0.121569, green: 0.121569, blue: 0.133333, alpha: 1.000000)
        )

        public static let surface = ColorToken(
            light: RGBA(red: 1.000000, green: 1.000000, blue: 1.000000, alpha: 1.000000),
            dark: RGBA(red: 0.141176, green: 0.141176, blue: 0.149020, alpha: 1.000000)
        )

        public static let surfaceRaised = ColorToken(
            light: RGBA(red: 1.000000, green: 1.000000, blue: 1.000000, alpha: 1.000000),
            dark: RGBA(red: 0.164706, green: 0.164706, blue: 0.176471, alpha: 1.000000)
        )

        public static let sidebarMac = ColorToken(
            light: RGBA(red: 1.000000, green: 1.000000, blue: 1.000000, alpha: 0.650980),
            dark: RGBA(red: 0.227451, green: 0.227451, blue: 0.247059, alpha: 0.501961)
        )

        public static let textPrimary = ColorToken(
            light: RGBA(red: 0.113725, green: 0.113725, blue: 0.121569, alpha: 1.000000),
            dark: RGBA(red: 0.960784, green: 0.960784, blue: 0.968627, alpha: 1.000000)
        )

        public static let textSecondary = ColorToken(
            light: RGBA(red: 0.235294, green: 0.235294, blue: 0.262745, alpha: 1.000000),
            dark: RGBA(red: 0.921569, green: 0.921569, blue: 0.960784, alpha: 0.600000)
        )

        public static let textTertiary = ColorToken(
            light: RGBA(red: 0.431373, green: 0.431373, blue: 0.450980, alpha: 1.000000),
            dark: RGBA(red: 0.921569, green: 0.921569, blue: 0.960784, alpha: 0.360784)
        )

        public static let separator = ColorToken(
            light: RGBA(red: 0.000000, green: 0.000000, blue: 0.000000, alpha: 0.101961),
            dark: RGBA(red: 1.000000, green: 1.000000, blue: 1.000000, alpha: 0.078431)
        )

        public static let glass = ColorToken(
            light: RGBA(red: 1.000000, green: 1.000000, blue: 1.000000, alpha: 0.701961),
            dark: RGBA(red: 1.000000, green: 1.000000, blue: 1.000000, alpha: 0.078431)
        )

        public static let glassStroke = ColorToken(
            light: RGBA(red: 0.000000, green: 0.000000, blue: 0.000000, alpha: 0.078431),
            dark: RGBA(red: 1.000000, green: 1.000000, blue: 1.000000, alpha: 0.149020)
        )

        public static let accent = ColorToken(
            light: RGBA(red: 0.000000, green: 0.478431, blue: 1.000000, alpha: 1.000000),
            dark: RGBA(red: 0.039216, green: 0.517647, blue: 1.000000, alpha: 1.000000)
        )

        public static let onAccent = ColorToken(
            light: RGBA(red: 1.000000, green: 1.000000, blue: 1.000000, alpha: 1.000000),
            dark: RGBA(red: 1.000000, green: 1.000000, blue: 1.000000, alpha: 1.000000)
        )

        public static let success = ColorToken(
            light: RGBA(red: 0.141176, green: 0.541176, blue: 0.239216, alpha: 1.000000),
            dark: RGBA(red: 0.188235, green: 0.819608, blue: 0.345098, alpha: 1.000000)
        )

        public static let danger = ColorToken(
            light: RGBA(red: 0.843137, green: 0.000000, blue: 0.082353, alpha: 1.000000),
            dark: RGBA(red: 1.000000, green: 0.270588, blue: 0.227451, alpha: 1.000000)
        )

        public static let warning = ColorToken(
            light: RGBA(red: 0.698039, green: 0.313725, blue: 0.000000, alpha: 1.000000),
            dark: RGBA(red: 1.000000, green: 0.839216, blue: 0.039216, alpha: 1.000000)
        )

    }

    /// Per-instance icon accents that come from the pen instance tiles.
    public enum InstancePalette {
        public static let forest = RGBA(red: 0.243137, green: 0.352941, blue: 0.266667, alpha: 1.000000)
        public static let copper = RGBA(red: 0.419608, green: 0.321569, blue: 0.211765, alpha: 1.000000)
        public static let slate = RGBA(red: 0.290196, green: 0.419608, blue: 0.541176, alpha: 1.000000)
        public static let violet = RGBA(red: 0.356863, green: 0.290196, blue: 0.478431, alpha: 1.000000)
        public static let clay = RGBA(red: 0.478431, green: 0.352941, blue: 0.290196, alpha: 1.000000)
        public static let lagoon = RGBA(red: 0.184314, green: 0.435294, blue: 0.470588, alpha: 1.000000)
        public static let sand = RGBA(red: 0.427451, green: 0.372549, blue: 0.290196, alpha: 1.000000)
        public static let moss = RGBA(red: 0.294118, green: 0.372549, blue: 0.270588, alpha: 1.000000)
        public static let ash = RGBA(red: 0.352941, green: 0.352941, blue: 0.290196, alpha: 1.000000)
    }

    /// Loader tile colors used by the instance library and preview.
    public enum LoaderPalette {
        public static let fabric = RGBA(red: 0.788235, green: 0.705882, blue: 0.541176, alpha: 1.000000)
        public static let quilt = RGBA(red: 0.788235, green: 0.705882, blue: 0.541176, alpha: 1.000000)
        public static let forge = RGBA(red: 0.545098, green: 0.568627, blue: 0.600000, alpha: 1.000000)
        public static let neoforge = RGBA(red: 0.878431, green: 0.545098, blue: 0.235294, alpha: 1.000000)
        public static let vanilla = RGBA(red: 0.435294, green: 0.686275, blue: 0.415686, alpha: 1.000000)
        public static let optifine = RGBA(red: 0.725490, green: 0.541176, blue: 0.388235, alpha: 1.000000)
    }

    public enum Radius {
        public static let window: CGFloat = 26
        public static let control: CGFloat = 9
        public static let pill: CGFloat = 18
        public static let card: CGFloat = 16
        public static let icon: CGFloat = 7
    }

    public enum Spacing {
        /// tokens.json `spacing.1`
        public static let s1: CGFloat = 2
        /// tokens.json `spacing.2`
        public static let s2: CGFloat = 4
        /// tokens.json `spacing.3`
        public static let s3: CGFloat = 6
        /// tokens.json `spacing.4`
        public static let s4: CGFloat = 8
        /// tokens.json `spacing.5`
        public static let s5: CGFloat = 10
        /// tokens.json `spacing.6`
        public static let s6: CGFloat = 12
        /// tokens.json `spacing.7`
        public static let s7: CGFloat = 14
        /// tokens.json `spacing.8`
        public static let s8: CGFloat = 16
        /// tokens.json `spacing.9`
        public static let s9: CGFloat = 20
        /// tokens.json `spacing.10`
        public static let s10: CGFloat = 24
        /// tokens.json `spacing.11`
        public static let s11: CGFloat = 32
    }

    public enum Typography {
        public static let caption: CGFloat = 11
        public static let small: CGFloat = 12
        public static let body: CGFloat = 13
        public static let subhead: CGFloat = 15
        public static let heading: CGFloat = 17
        public static let title: CGFloat = 22
        public static let page: CGFloat = 28
        public static let hero: CGFloat = 32
    }

    public enum Metrics {
        public static let titlebarHeight: CGFloat = 38
        public static let trafficLightX: CGFloat = 18
        public static let trafficLightY: CGFloat = 24
        public static let trafficLightDiameter: CGFloat = 12
        public static let trafficLightGap: CGFloat = 8
    }
}
