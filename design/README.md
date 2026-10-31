# Design sources

`fograin.pen` stays the visual source of truth. This folder holds the assets
and tokens that survive the native-UI rewrite.

- `tokens.json` — shared colors, type scale, spacing, radii and window chrome
  measurements. Native apps generate their own constants from this file; do not
  copy the hex values into Swift/Kotlin/C# by hand.
- `assets/` — exported bitmap art that is not reproducible from code.
- `app-icon.png` — placeholder application icon until the brand asset lands.
- `screens/` — place exported pen screens or platform reference images here.

The pen document itself lives at `~/.pencil/documents/.../fograin.pen`; it is
not committed because it is encrypted and binary.

## Token generation

Run `python3 scripts/generate_tokens.py` after editing `design/tokens.json`.
