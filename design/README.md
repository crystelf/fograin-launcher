# Design sources

`fograin.pen` is the page and element reference, not a control specification.
It says which elements a screen contains and how they are grouped; the shared
Compose UI implements them with Material 3, and the desktop shell supplies the
platform window chrome. This folder holds the assets and tokens that survive
the UI rewrite.

- `tokens.json` — shared colors, type scale, spacing, radii and window chrome
  measurements. `scripts/generate_tokens.py` generates one Kotlin file for the
  shared Compose module; do not copy hex values into Kotlin by hand.
- `assets/` — exported bitmap art that is not reproducible from code.
- `app-icon.png` — placeholder application icon until the brand asset lands.
- `screens/` — place exported pen screens or platform reference images here.

The pen document itself lives at `~/.pencil/documents/.../fograin.pen`; it is
not committed because it is encrypted and binary.

## Token generation

Run `python3 scripts/generate_tokens.py` after editing `design/tokens.json`.
The generated Kotlin file goes to
`apps/compose/shared/src/commonMain/kotlin/com/fograin/launcher/generated/Tokens.kt`.
