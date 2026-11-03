# Fograin macOS

Native macOS client: SwiftUI for the shell and pages, AppKit only where SwiftUI
needs a bridge (window setup, frame autosave, native search field).

The pen file is an **element inventory**, not a control specification. The
toolbar, search field, segmented controls and menus are the system's controls:
`NSToolbar`, `NSSearchField`, `NSSegmentedControl`, `NSMenu`, `NSButton`. Do not
draw custom buttons or glass capsules to imitate the pen.

## Status (M1)

- `NavigationSplitView` shell with native traffic lights and titlebar.
- Home: hero, recent instances, download queue, runtime summary, version news.
  A single click selects the current instance (updating the hero); double click
  or the context menu enters the instance library.
- Instance library: grouped list, sort menu, search, collapsible loader groups,
  quick preview with instance details.
- Appearance: system / light / dark from `launcher.toml`, written through
  `fg-ffi`; the native window appearance changes immediately.
- Native menus and shortcuts: ⌘N, ⇧⌘I, ⇧⌘O, ⇧⌘D, ⌘F, ⌘1–⌘8.
- `NSOpenPanel` directory import, data-directory reveal, Dock icon.
- Static fixture data only; real instance/download/auth data arrives in later
  milestones.

## Compatibility

- Minimum: macOS 14 (Sonoma).
- macOS 26/27: the toolbar action group is a native `NSToolbarItemGroup` whose
  subitems carry SF Symbol images, so the system renders one capsule with the
  system material. It is installed on the window toolbar by
  `ToolbarGroupInstaller`. Content actions still use `.glass` /
  `.glassProminent` buttons. Building with Xcode 26+ (Swift 6.2) is required for
  the glass button styles; older toolchains still build.
- macOS 14/15 and older Xcode/SDKs: the native control group and the bordered
  capsule/circle fallbacks still render; no custom glass is involved.
- `FOGRAIN_DISABLE_GLASS=1` forces the bordered fallback for screenshots or
  troubleshooting.

## Build

The client links the Rust core as a static library, so use the repository script:

```bash
./scripts/build-macos.sh          # build Rust core + Swift client
./scripts/build-macos.sh --run    # run
./scripts/build-macos.sh --test   # Swift contract/model tests
```

The script builds `fg-ffi`, copies `libfg_ffi.a` to
`apps/macos/.build/rust/lib/`, and then drives SwiftPM. Running `swift build`
directly will fail at link time. When `xcode-select` points at CommandLineTools,
the script prefers `/Applications/Xcode.app` or `/Applications/Xcode-beta.app`
because SwiftUI macros need a full Xcode toolchain.

The package also passes an explicit `-platform_version macos 14.0 <SDK>` to the
app link. Rust objects in the static library have no SDK version, so without
this SwiftPM records the deployment target (14.0) in `LC_BUILD_VERSION`; macOS
then treats the app as a legacy build and disables Liquid Glass. Verify a build
with:

```bash
otool -l apps/macos/.build/debug/Fograin | grep -A4 LC_BUILD_VERSION
#        minos 14.0
#          sdk 27.0
```

If `sdk` is `14.0`, the binary is old; rebuild before judging the glass.

## Page transitions

The common toolbar search and the native action group are installed once and
kept across Home/Library changes; only the page-specific view/sort picker swaps.
The action group is an `NSToolbarItemGroup` built from image subitems, not a
custom view, so the system owns its capsule material and it is not rebuilt when
the page changes. The detail page crossfades for 160 ms, and the search field
consumes its focus request so a page rebuild cannot steal focus and flash a
focus ring.

## Layout

```text
Package.swift
Sources/CFograinFFI/        # C header + module for the Rust static library
Sources/FograinKit/         # models, formatters, FFI bridge, generated tokens
Sources/Fograin/            # AppKit/SwiftUI shell
  App/                      # AppModel, navigation, sidebar, commands, delegate
  Design/                   # token mapping, native capsule helper
  Features/                 # home, library, settings, about, placeholders
  Platform/                 # window setup, native search field, snapshot helper
Tests/FograinKitTests/      # FFI contract, models, sorting/filtering, formatting
```

## Visual checks

`FOGRAIN_SNAPSHOT=/tmp/shot.png` (DEBUG builds only) writes the window content
and chrome to PNG files after launch. Liquid Glass windows cannot be captured
with `cacheDisplay`; pass `FOGRAIN_DISABLE_GLASS=1` when reviewing layout.

```bash
FOGRAIN_HOME=/tmp/fograin-dev \
FOGRAIN_DISABLE_GLASS=1 \
FOGRAIN_SNAPSHOT=/tmp/home.png \
FOGRAIN_SNAPSHOT_SECTION=library \
FOGRAIN_SNAPSHOT_EXIT=1 \
./scripts/build-macos.sh --run
```

## Rules

- No WebView.
- Use SF Symbols; do not ship a web icon font.
- Read colors and metrics from `design/tokens.json` through generated constants.
- Native windows keep the macOS traffic lights; do not draw fake ones.
- Do not hand-draw a system control to copy the pen.
