# Fograin macOS

Native macOS client: SwiftUI for the app shell and pages, AppKit only where
SwiftUI needs a bridge (window chrome, vibrancy, traffic lights).

## Build

```bash
swift build --package-path apps/macos
swift run --package-path apps/macos
```

The current package is a development executable. Packaging as a signed `.app`
with an Xcode project/bundle comes after the native shell is approved.

## Rules

- No WebView.
- Use SF Symbols; do not ship a web icon font.
- Read colors and metrics from `design/tokens.json` through generated constants.
- Native windows keep the macOS traffic lights; do not draw fake ones.
