#!/usr/bin/env python3
"""Generate the Compose design-token constants from design/tokens.json."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOKENS = json.loads((ROOT / "design" / "tokens.json").read_text())

ANDROID = TOKENS["android"]
DARK = ANDROID["dark"]
LIGHT = ANDROID["light"]
RADII = TOKENS["radii"]["android"]
SIZES = TOKENS["typography"]["sizes"]
SPACING = TOKENS["spacing"]
PALETTE = TOKENS["content"]["instancePalette"]
LOADER_PALETTE = TOKENS["content"]["loaderPalette"]

OUTPUT = (
    ROOT
    / "apps/compose/shared/src/commonMain/kotlin/com/fograin/launcher/generated/Tokens.kt"
)


def color(hex_value: str) -> str:
    value = hex_value.strip().lstrip("#")
    alpha = int(value[6:8], 16) if len(value) == 8 else 255
    red, green, blue = (int(value[index : index + 2], 16) for index in (0, 2, 4))
    return f"Color(0x{alpha:02X}{red:02X}{green:02X}{blue:02X})"


def color_block(values: dict[str, str], indent: str = "        ") -> list[str]:
    return [f"{indent}val {key} = {color(value)}" for key, value in values.items()]


lines = [
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
lines += color_block(DARK)
lines += ["    }", "", "    object Light {"]
lines += color_block(LIGHT)
lines += ["    }", "", "    object Type {"]
for key, value in SIZES.items():
    lines.append(f"        val {key} = {value}.sp")
lines += ["    }", "", "    object Space {"]
for index, value in enumerate(SPACING.values(), start=1):
    lines.append(f"        val s{index} = {value}.dp")
lines += ["    }", "", "    object Radius {"]
for key, value in RADII.items():
    lines.append(f"        val {key} = {value}.dp")
lines += ["    }", "", "    object Palette {"]
for key, value in PALETTE.items():
    lines.append(f"        val {key} = {color(value)}")
for key, value in LOADER_PALETTE.items():
    name = f"loader{key[0].upper()}{key[1:]}"
    lines.append(f"        val {name} = {color(value)}")
lines += ["    }", "}", ""]

OUTPUT.parent.mkdir(parents=True, exist_ok=True)
OUTPUT.write_text("\n".join(lines))
print(OUTPUT.relative_to(ROOT))
