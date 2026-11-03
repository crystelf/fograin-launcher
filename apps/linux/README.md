# Fograin Linux

Native GNOME client: Rust + GTK4 + libadwaita. It links the shared `fg-*`
crates directly and does not use a WebView or a CSS skin: every control is a
stock GTK/libadwaita widget.

## Build

Ubuntu 24.04+ / Fedora:

```bash
sudo apt install libgtk-4-dev libadwaita-1-dev build-essential pkg-config
cargo run --manifest-path apps/linux/Cargo.toml
```

This app is intentionally a separate Cargo workspace so a macOS/Windows
`cargo test --workspace` does not try to compile GTK. From `apps/linux`:

```bash
cargo fmt
cargo clippy --all-targets -- -D warnings
cargo test
```

## M1 status

The shell from `docs/design.md` §M1 is implemented:

- `Adw.ApplicationWindow` with `Adw.NavigationSplitView`, a native sidebar
  `Adw.HeaderBar`, the app menu and stock GTK window controls.
- Home page: hero banner (`Gtk.Overlay` + `Gtk.Picture`), launch actions,
  recent/favourite/all instance lists, download cards with `Gtk.ProgressBar`,
  and an environment block built from `Adw.ActionRow` / `Adw.SwitchRow`.
- Instance library: `Adw.OverlaySplitView` list/detail split. Below 1180 px it
  collapses to a list-first compact layout; picking a row shows the detail pane
  with a native back button. Includes `Gtk.SearchEntry` filtering, a sort menu,
  a loader-grouped list, and a detail pane with `Adw.PreferencesGroup`.
- Theme: `appearance.theme` is read through `fg-core` in `startup`, before the
  first frame, and written back atomically when changed from the primary menu
  or the settings page (`Ctrl+,`).
- Native `Gtk.FileDialog` for “导入现有实例…”, `Gio.AppInfo` for opening the
  data directory, and `Adw.AboutDialog` for about.
- Honest `Adw.StatusPage` placeholders for servers (M9), downloads (M8),
  Java (M6) and accounts (M7).
- Shortcuts: `Ctrl+1…7` sections, `Ctrl+F` library search, `Ctrl+I` import,
  `Ctrl+,` settings, `F1` about, `Ctrl+Q` quit.
- Accessible names on navigation rows, instance rows and icon-only buttons; the
  home columns stack vertically below 1000 px.

Instance data, downloads, the environment summary and the account are static
fixtures that mirror the pen screens; M4 replaces the instance data, later
milestones wire the remaining back ends. Buttons that would launch or install
show a milestone toast instead of pretending to work.

## Design tokens

`build.rs` reads `design/tokens.json` at compile time and emits the complete
GNOME token set into `OUT_DIR`: GNOME accent, content palettes, contrast-safe
tile text colors, radii, spacing and type sizes. Chrome colors come from the
Adwaita named palette (`@card_bg_color`, …), so the client follows the user's
GNOME theme; only content colors such as instance tiles come from the token
file.

`src/generated/tokens.rs` from `scripts/generate_tokens.py` is ignored and no
longer compiled: the shared generator currently emits only a flat subset for
Linux, while this client also needs the palettes and metrics. The build-time
generator keeps a single source of truth without a checked-in copy that can
drift. When the shared script learns the full GNOME set, switching back is a
one-line change.

## Layout

```
src/
├─ main.rs            GApplication, theme startup, accelerators
├─ app.rs             window shell, sidebar, actions, menu
├─ design.rs          token module + token-derived GTK stylesheet
├─ dev.rs             screenshot harness for the visual pass
├─ events.rs          page → shell events
├─ models.rs          M1 fixtures, grouping/sorting/formatting (+ unit tests)
├─ navigation.rs      sections, sidebar groups, milestone copy
├─ pages/             home, library, detail pane, settings, placeholders
├─ platform/          Adw.StyleManager theme adapter, native file dialogs
└─ widgets.rs         rows, tiles, banner, section headers
```

## Visual verification

`FOGRAIN_SCREENSHOT=<path>` renders the window to a PNG after the first frame
and exits; `FOGRAIN_PAGE=library`, `FOGRAIN_SIZE=1000x700` and
`FOGRAIN_THEME=light` cover the acceptance matrix from `docs/design.md` §4.4
without scripting a window manager:

```bash
FOGRAIN_HOME=/tmp/fograin-demo FOGRAIN_PAGE=library \
FOGRAIN_SCREENSHOT=/tmp/library.png \
  cargo run --manifest-path apps/linux/Cargo.toml
```

Reference renders from the M1 pass live in [`docs/screenshots`](docs/screenshots);
they were captured with the GTK4 backend on the development host, while the Linux
CI job builds the same sources against GTK4/libadwaita on Ubuntu.

## Rules

- Use libadwaita and GTK widgets; no custom-drawn imitations of native controls.
- Native header bar and window controls; no fake macOS traffic lights.
- Colors and metrics come from `design/tokens.json` through generated constants;
  do not copy hex values into Rust or CSS by hand.
- libsecret, Unity LauncherEntry, portal integration and packaging land with
  M7/M10.
