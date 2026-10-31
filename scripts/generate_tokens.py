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
swift = ["// Generated from design/tokens.json. Do not edit by hand.", "", "import SwiftUI", "", "enum FograinGenerated {"]
for key, value in [
    ("accent", DARK["accent"]),
    ("textPrimary", DARK["textPrimary"]),
    ("textSecondary", DARK["textSecondary"]),
    ("textTertiary", DARK["textTertiary"]),
    ("separator", DARK["separator"]),
    ("success", DARK["success"]),
    ("danger", DARK["danger"]),
]:
    r, g, b = rgb(value)
    a = alpha(value) / 255
    swift.append(
        f"    static let {key} = Color(.sRGB, red: {r / 255:.6f}, "
        f"green: {g / 255:.6f}, blue: {b / 255:.6f}, opacity: {a:.6f})"
    )
swift += ["}", ""]
write("apps/macos/Sources/Fograin/Generated/GeneratedTokens.swift", "\n".join(swift))

# Android
kotlin = ["// Generated from design/tokens.json. Do not edit by hand.", "", "package com.fograin.launcher.generated", "", "import androidx.compose.ui.graphics.Color", "", "object FograinGenerated {"]
for key, value in [
    ("Accent", DARK["accent"]),
    ("TextPrimary", DARK["textPrimary"]),
    ("TextSecondary", DARK["textSecondary"]),
    ("TextTertiary", DARK["textTertiary"]),
    ("Separator", DARK["separator"]),
    ("Success", DARK["success"]),
    ("Danger", DARK["danger"]),
]:
    r, g, b = rgb(value)
    a = alpha(value)
    kotlin.append(f"    val {key} = Color(0x{a:02X}{r:02X}{g:02X}{b:02X})")
kotlin += ["}", ""]
write("apps/android/app/src/main/java/com/fograin/launcher/generated/Tokens.kt", "\n".join(kotlin))

# Windows
csharp = ["// Generated from design/tokens.json. Do not edit by hand.", "", "namespace Fograin.Windows.Generated;", "", "public static class DesignTokens", "{"]
for key, value in [
    ("Accent", DARK["accent"]),
    ("TextPrimary", DARK["textPrimary"]),
    ("TextSecondary", DARK["textSecondary"]),
    ("TextTertiary", DARK["textTertiary"]),
    ("Separator", DARK["separator"]),
    ("Success", DARK["success"]),
    ("Danger", DARK["danger"]),
]:
    r, g, b = rgb(value)
    a = alpha(value)
    csharp.append(f"    public static readonly Windows.UI.Color {key} = Windows.UI.Color.FromArgb(0x{a:02X}, 0x{r:02X}, 0x{g:02X}, 0x{b:02X});")
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
