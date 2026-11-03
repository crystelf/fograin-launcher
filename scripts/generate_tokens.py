#!/usr/bin/env python3
"""Generate native design-token constants from design/tokens.json."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOKENS = json.loads((ROOT / "design" / "tokens.json").read_text())
DARK = TOKENS["desktop"]["dark"]


def rgb(hex_value: str) -> tuple[int, int, int]:
    value = hex_value.strip().lstrip("#")
    if len(value) == 8:  # drop alpha for APIs that take RGB only
        value = value[:6]
    return int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16)


def alpha(hex_value: str) -> int:
    value = hex_value.strip().lstrip("#")
    return int(value[6:8], 16) if len(value) == 8 else 255


def write(path: str, text: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text)
    print(path)


# macOS
#
# The Swift side needs both themes so it can resolve colors through a dynamic
# NSColor provider, plus the radii/spacing/type scale and window chrome metrics.
LIGHT = TOKENS["desktop"]["light"]
MACOS = TOKENS["platforms"]["macos"]
MAC_RADII = TOKENS["radii"]["macos"]
SIZES = TOKENS["typography"]["sizes"]
SPACING = TOKENS["spacing"]
PALETTE = TOKENS["content"]["instancePalette"]
LOADER_PALETTE = TOKENS["content"]["loaderPalette"]

SWIFT_COLOR_KEYS = [
    "background",
    "surface",
    "surfaceRaised",
    "sidebarMac",
    "textPrimary",
    "textSecondary",
    "textTertiary",
    "separator",
    "glass",
    "glassStroke",
    "accent",
    "onAccent",
    "success",
    "danger",
    "warning",
]


def rgba(value: str) -> str:
    r, g, b = rgb(value)
    a = alpha(value) / 255
    return (
        f"RGBA(red: {r / 255:.6f}, green: {g / 255:.6f}, "
        f"blue: {b / 255:.6f}, alpha: {a:.6f})"
    )


swift = [
    "// Generated from design/tokens.json. Do not edit by hand.",
    "// Regenerate with `python3 scripts/generate_tokens.py`.",
    "",
    "import CoreGraphics",
    "",
    "/// Straight sRGB components. The Swift UI turns these into dynamic NSColors.",
    "public struct RGBA: Sendable, Equatable {",
    "    public let red: Double",
    "    public let green: Double",
    "    public let blue: Double",
    "    public let alpha: Double",
    "",
    "    public init(red: Double, green: Double, blue: Double, alpha: Double) {",
    "        self.red = red",
    "        self.green = green",
    "        self.blue = blue",
    "        self.alpha = alpha",
    "    }",
    "}",
    "",
    "public struct ColorToken: Sendable, Equatable {",
    "    public let light: RGBA",
    "    public let dark: RGBA",
    "",
    "    public init(light: RGBA, dark: RGBA) {",
    "        self.light = light",
    "        self.dark = dark",
    "    }",
    "",
    "    public func rgba(dark isDark: Bool) -> RGBA {",
    "        isDark ? dark : light",
    "    }",
    "}",
    "",
    "public enum FograinGenerated {",
    "    public enum Color {",
]
for key in SWIFT_COLOR_KEYS:
    swift += [
        f"        public static let {key} = ColorToken(",
        f"            light: {rgba(LIGHT[key])},",
        f"            dark: {rgba(DARK[key])}",
        "        )",
        "",
    ]
swift += ["    }", ""]

swift += ["    /// Per-instance icon accents that come from the pen instance tiles.", "    public enum InstancePalette {"]
for key, value in PALETTE.items():
    swift.append(f"        public static let {key} = {rgba(value)}")
swift += ["    }", ""]

swift += ["    /// Loader tile colors used by the instance library and preview.", "    public enum LoaderPalette {"]
for key, value in LOADER_PALETTE.items():
    swift.append(f"        public static let {key} = {rgba(value)}")
swift += ["    }", ""]

swift += ["    public enum Radius {"]
for key, value in MAC_RADII.items():
    swift.append(f"        public static let {key}: CGFloat = {value}")
swift += ["    }", ""]

swift += ["    public enum Spacing {"]
for index, value in enumerate(SPACING.values(), start=1):
    swift.append(f"        /// tokens.json `spacing.{index}`")
    swift.append(f"        public static let s{index}: CGFloat = {value}")
swift += ["    }", ""]

swift += ["    public enum Typography {"]
for key, value in SIZES.items():
    swift.append(f"        public static let {key}: CGFloat = {value}")
swift += ["    }", ""]

swift += [
    "    public enum Metrics {",
    f"        public static let titlebarHeight: CGFloat = {MACOS['titlebarHeight']}",
    f"        public static let trafficLightX: CGFloat = {MACOS['trafficLights']['x']}",
    f"        public static let trafficLightY: CGFloat = {MACOS['trafficLights']['y']}",
    f"        public static let trafficLightDiameter: CGFloat = {MACOS['trafficLights']['diameter']}",
    f"        public static let trafficLightGap: CGFloat = {MACOS['trafficLights']['gap']}",
    "    }",
    "}",
    "",
]
write(
    "apps/macos/Sources/FograinKit/Generated/GeneratedTokens.swift",
    "\n".join(swift),
)


# Android
#
# Compose needs the complete Material 3 color scheme plus the type scale,
# spacing, radii and content palettes from the pen.
ANDROID = TOKENS["android"]
ANDROID_DARK = ANDROID["dark"]
ANDROID_LIGHT = ANDROID["light"]
ANDROID_RADII = TOKENS["radii"]["android"]
ANDROID_SIZES = TOKENS["typography"]["sizes"]
ANDROID_SPACING = TOKENS["spacing"]
ANDROID_PALETTE = TOKENS["content"]["instancePalette"]
ANDROID_LOADER_PALETTE = TOKENS["content"]["loaderPalette"]


def kotlin_color(value: str) -> str:
    r, g, b = rgb(value)
    a = alpha(value)
    return f"Color(0x{a:02X}{r:02X}{g:02X}{b:02X})"


def kotlin_color_block(values: dict[str, str]) -> list[str]:
    return [f"        val {key} = {kotlin_color(value)}" for key, value in values.items()]


kotlin = [
    "// Generated from design/tokens.json. Do not edit by hand.",
    "// Regenerate with `python3 scripts/generate_tokens.py`.",
    "",
    "package com.fograin.launcher.generated",
    "",
    "import androidx.compose.ui.graphics.Color",
    "import androidx.compose.ui.unit.dp",
    "import androidx.compose.ui.unit.sp",
    "",
    "object FograinTokens {",
    "    object Dark {",
]
kotlin += kotlin_color_block(ANDROID_DARK)
kotlin += ["    }", "", "    object Light {"]
kotlin += kotlin_color_block(ANDROID_LIGHT)
kotlin += ["    }", "", "    object Type {"]
for key, value in ANDROID_SIZES.items():
    kotlin.append(f"        val {key} = {value}.sp")
kotlin += ["    }", "", "    object Space {"]
for index, value in enumerate(ANDROID_SPACING.values(), start=1):
    kotlin.append(f"        val s{index} = {value}.dp")
kotlin += ["    }", "", "    object Radius {"]
for key, value in ANDROID_RADII.items():
    kotlin.append(f"        val {key} = {value}.dp")
kotlin += ["    }", "", "    object Palette {"]
for key, value in ANDROID_PALETTE.items():
    kotlin.append(f"        val {key} = {kotlin_color(value)}")
for key, value in ANDROID_LOADER_PALETTE.items():
    kotlin.append(f"        val loader{key[0].upper() + key[1:]} = {kotlin_color(value)}")
kotlin += ["    }", "}", ""]
write("apps/android/app/src/main/java/com/fograin/launcher/generated/Tokens.kt", "\n".join(kotlin))

# The starting window is drawn by the system before Compose exists. Values
# follow the system theme; the activity overrides the window background from the
# generated colors when the in-app appearance differs from the system.
write(
    "apps/android/app/src/main/res/values/colors.xml",
    "\n".join(
        [
            '<?xml version="1.0" encoding="utf-8"?>',
            "<resources>",
            f'    <color name="fograin_window_background">{ANDROID_LIGHT["surface"]}</color>',
            "</resources>",
            "",
        ]
    ),
)
write(
    "apps/android/app/src/main/res/values-night/colors.xml",
    "\n".join(
        [
            '<?xml version="1.0" encoding="utf-8"?>',
            "<resources>",
            f'    <color name="fograin_window_background">{ANDROID_DARK["surface"]}</color>',
            "</resources>",
            "",
        ]
    ),
)

# Windows
#
# The client namespace ends in .Windows, so types from the Windows SDK must be
# qualified with global:: (inside Fograin.Windows, `Windows` resolves to the
# enclosing namespace, not the platform namespace).
WIN = TOKENS["platforms"]["win11"]
csharp = ["// Generated from design/tokens.json. Do not edit by hand.", "", "namespace Fograin.Windows.Generated;", "", "public static class DesignTokens", "{"]
for key, value in [
    ("Accent", WIN["accent"]),
    ("TextPrimary", DARK["textPrimary"]),
    ("TextSecondary", DARK["textSecondary"]),
    ("TextTertiary", DARK["textTertiary"]),
    ("Separator", DARK["separator"]),
    ("Success", DARK["success"]),
    ("Danger", DARK["danger"]),
]:
    r, g, b = rgb(value)
    a = alpha(value)
    csharp.append(f"    public static readonly global::Windows.UI.Color {key} = global::Windows.UI.Color.FromArgb(0x{a:02X}, 0x{r:02X}, 0x{g:02X}, 0x{b:02X});")
csharp += ["}", ""]
write("apps/windows/Fograin.Windows/Generated/DesignTokens.cs", "\n".join(csharp))

# Linux GTK: keep raw RGB so the app can choose gdk::RGBA or CSS provider later.
rust = ["// Generated from design/tokens.json. Do not edit by hand.", ""]
for key, value in [
    ("ACCENT", DARK["accent"]),
    ("TEXT_PRIMARY", DARK["textPrimary"]),
    ("TEXT_SECONDARY", DARK["textSecondary"]),
    ("TEXT_TERTIARY", DARK["textTertiary"]),
    ("SEPARATOR", DARK["separator"]),
    ("SUCCESS", DARK["success"]),
    ("DANGER", DARK["danger"]),
]:
    r, g, b = rgb(value)
    rust.append(f"pub const {key}: (u8, u8, u8) = (0x{r:02X}, 0x{g:02X}, 0x{b:02X});")
rust += ["", "pub const WINDOW_RADIUS: u16 = 12;", ""]
write("apps/linux/src/generated/tokens.rs", "\n".join(rust))
