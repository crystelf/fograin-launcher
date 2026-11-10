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

## M4 status

The M4 instance, download and session flows are wired to the Rust core. The
client links `fg-ffi`'s `Service` directly instead of re-implementing the JSON
contract: RPCs run on a three-thread worker pool, and the GTK main loop pumps
their results and the core event queue. macOS goes through the C ABI, but both
clients issue the same commands and consume the same events.

- **Core bridge** (`src/core.rs`, `src/state.rs`): `instances.list`,
  `app.snapshot`, `gameConfig.get`, `tasks.list`, `versions.list`,
  `version.plan`, `install.start`, `launch.start`, `session.stop`,
  `instances.settings.get/set`, `instances.favorite`, `instances.delete`,
  `instances.dir` and `instances.import`; task progress and `game.*` events are
  drained on a 50 ms GLib timer.
- **Home**: the hero follows the selected instance and reports install, download
  and session state; its primary split button toggles 开始游戏 / 停止游戏 (the
  titlebar keeps only the scheme-style instance item that opens 游戏设置).
  Recent/favourite/all is an accent segmented filter built from native toggle
  buttons, and the task queue, Java count, memory summary and session type all
  read live data. The empty state routes to the download page.
- **Instance library**: loader-grouped live instances, `Gtk.SearchEntry`
  filtering, a sort menu, a `Gtk.ListBox` table and an
  `Adw.OverlaySplitView` preview pane. Single click selects, double click (or
  row menu) opens 游戏设置. Below 1180 px the split collapses to a full-width
  list; the first row click reveals the detail overlay with a native back
  button.
- **Downloads** follows the HMCL/PCL flow used by macOS: the titlebar switches
  categories (游戏/模组/整合包/资源包/光影/数据包), the 游戏 tab loads
  `versions.list` for a channel (正式版/快照/愚人节/远古版本), selecting a
  version computes `version.plan` (Java major, file count, size, release date),
  and 安装 opens the native modal for game version, loader and instance name.
  The queue rail shows live task progress and aggregate speed. 原版 installs;
  the other loader cards are honest M5 placeholders. Below 1240 px the queue is
  hidden, the version/detail split collapses and the six-way segmented switcher
  becomes a native dropdown, so the page fits a 1000 px window without
  horizontal clipping.
- **游戏设置**: a native `Adw.Dialog` with 启动/显示/内容/维护 groups. It loads
  the effective values and `[override]` values, writes 内存 / JVM 参数 / 全屏 /
  分辨率 through `instances.settings.set` (`null` restores inheritance), opens
  the real instance directory (including imported external `.minecraft`
  folders) and confirms before deleting the instance.
- **Sessions**: `launch.start` and `session.stop` drive the hero start/stop state;
  `game.started`, `game.windowReady`, `game.line` and `game.exited` update the
  session, show native toasts/alert dialogs, refresh the instance row and record
  play time. Pressing 开始游戏 on an incomplete instance starts its resource
  completion and jumps to the queue; a finished install opens the new instance
  in 游戏设置.
- **Import**: “导入现有实例…” uses the XDG portal `Gtk.FileDialog` and registers
  the chosen `.minecraft` directory through `instances.import`.
- **Feedback**: success/info/warning notices use `Adw.Toast`; failures use an
  `Adw.AlertDialog` instead of a vanishing capsule. Theme switching still goes
  through `Adw.StyleManager` before the first frame and from the primary menu.

Reserved for later milestones: Forge/NeoForge/Fabric/Quilt/OptiFine installers
(M5), Java auto-download (M6), accounts (M7), mods/resource packs/shaders
(M8), worlds and packaging (M9/M10). Unity LauncherEntry progress is not wired
yet; the task queue is the source of truth.

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
├─ main.rs            GApplication, theme startup, window size, accelerators
├─ app.rs             window shell, sidebar, actions, menu, breakpoints
├─ core.rs            fg-ffi service bridge: workers, callbacks, events
├─ state.rs           live instance/task/version/session state and M4 flows
├─ design.rs          token module + token-derived GTK stylesheet
├─ dev.rs             screenshot harness for the visual pass
├─ events.rs          page → shell events (navigation, toast, alert, dialogs)
├─ models.rs          wire decoding, grouping/sorting/formatting (+ tests)
├─ navigation.rs      sections, sidebar groups, milestone copy
├─ dialogs/           install wizard and 游戏设置
├─ pages/             home, library, detail pane, downloads, settings, stubs
├─ platform/          Adw.StyleManager theme, native file dialogs, host info
└─ widgets.rs         rows, tiles, hero card, progress, chips
```

## Visual verification

`FOGRAIN_SCREENSHOT=<path>` renders the window to a PNG after a delay and exits.
The size is applied before the first frame, so breakpoints are exercised:

```bash
# Live data root (or point FOGRAIN_HOME at a disposable directory)
FOGRAIN_HOME=/tmp/fograin-demo FOGRAIN_PAGE=library FOGRAIN_SIZE=1000x700 \
FOGRAIN_SCREENSHOT=/tmp/library.png \
  cargo run --manifest-path apps/linux/Cargo.toml
```

Useful switches for the acceptance matrix:

| Variable | Purpose |
| --- | --- |
| `FOGRAIN_PAGE=home|library|downloads|settings|servers` | section to open |
| `FOGRAIN_SIZE=1000x700` | window size before the first frame |
| `FOGRAIN_THEME=light|dark|system` | appearance action |
| `FOGRAIN_SCREENSHOT_DELAY=5000` | wait for the remote version manifest |

Reference renders from the M1 pass live in [`docs/screenshots`](docs/screenshots).
The M4 pass lives in [`docs/screenshots/m4`](docs/screenshots/m4): 1400×880 in
dark and light appearance, plus 1280×800 and 1000×700 responsive passes for the
compact library, compact downloads, empty states and the two modal surfaces.

## Rules

- Use libadwaita and GTK widgets; no custom-drawn imitations of native controls.
- Native header bar and window controls; no fake macOS traffic lights.
- Colors and metrics come from `design/tokens.json` through generated constants;
  do not copy hex values into Rust or CSS by hand.
- The Rust core never sees a GTK type; all business state stays in `fg-*` and
  `fg-ffi`.
- libsecret, Unity LauncherEntry, portal integration and packaging land with
  M7/M10.
