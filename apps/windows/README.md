# Fograin Windows

Native Windows 11 client: C# + WinUI 3 (Windows App SDK), Mica backdrop, the
native WinUI `TitleBar` control and `NavigationView`.

## Status: M4 wiring (code-level)

M4 (docs/design.md §14) is delivered for the Rust core and macOS. The Windows
client now consumes the same `fg-ffi` C ABI and mirrors the macOS flow:

- **Startup** — `fg_settings_snapshot` is read before the first window; the
  configured theme is applied to `Application.RequestedTheme`. Without
  `fg_ffi.dll` the shell starts with empty data and an `InfoBar` warning.
- **主页** — live current-instance hero with play/stop/settings/folder actions,
  a 最近/收藏/全部 instance list (list or grid layout) and the queue / runtime /
  release-headlines rail. `app.snapshot`, `gameConfig.get`, `versions.news` feed
  the rail; `task.*` events drive the queue and the taskbar progress.
- **实例库** — grouped live instance table (Fabric / Forge / NeoForge / 原版),
  sort menu, title-bar search, preview inspector with play, settings, folder and
  favorite actions. Double-click opens 游戏设置.
- **下载** — HMCL/PCL flow: category switcher (游戏 / 模组 / 整合包 / 资源包 /
  光影 / 数据包), version channel menu (正式版 / 快照 / 愚人节 / 远古版本),
  version search, `version.plan` preview, install dialog (version, loader cards,
  loader version placeholder, instance name) and the live task queue.
- **游戏设置** — memory, JVM arguments, fullscreen and resolution write the
  instance `[override]` section through `instances.settings.set`; `null` inherits
  `[game]`. Content rows owned by M8/M9 stay disabled. Maintenance can open the
  game directory or delete the instance.
- **Sessions** — `launch.start`, `session.stop`, `game.started`,
  `game.windowReady` and `game.exited` update the play/stop state, notices and
  `play_seconds` (the row refreshes through `instances.list`).
- **Import** — 游戏目录 opens the native `FolderPicker` and registers an existing
  `.minecraft` through `instances.import` without moving files.

Play/stop lives on the 主页 hero row rather than inside `TitleBar.Content`
(WinUI centers that slot): the information architecture and user path match the
macOS toolbar capsule, the chrome follows Windows conventions.

> **Verification caveat.** This change was prepared on macOS, where WinUI 3
> cannot be built or run (the Windows App SDK XAML compiler calls Windows-only
> APIs). The C# was compiled with Roslyn against the Windows App SDK 1.7 /
> Windows SDK projection assemblies, and the XAML was checked statically for
> binding paths, resources, event-handler signatures, enum values and element
> properties. The Windows CI job remains the first real `msbuild` execution; UI
> behaviour has not been exercised on Windows.

## Build

Requires Windows 10 1809+ and the .NET 8 SDK. Windows 11 is the M4 target
(Mica, Snap Layouts and the tall title bar need Windows 11).

```powershell
dotnet build apps/windows/Fograin.Windows/Fograin.Windows.csproj -c Debug -p:Platform=x64
```

If the build stops with only `MSB3073 ... XamlCompiler.exe ... exited with code
1`, `dotnet build` is hiding the XAML diagnostics: it runs the WinUI XAML
compiler out of process and reports nothing but the exit code. Build with the
Visual Studio MSBuild in that case — it runs the same compiler in process and
prints the actual XAML error:

```powershell
msbuild apps/windows/Fograin.Windows/Fograin.Windows.csproj /restore /p:Configuration=Debug /p:Platform=x64
```

CI uses the same MSBuild invocation (`.github/workflows/ci.yml`) and uploads
`obj/**/output.json` plus a binlog when the job fails.

The project is unpackaged (`WindowsPackageType=None`) with a self-contained
Windows App SDK; MSIX packaging is a later release step.

The shell works without the Rust core, but every data page needs it:

```powershell
cargo build --release -p fg-ffi
# Copy target\release\fg_ffi.dll next to Fograin.Windows.exe
# (the output folder under bin\<platform>\Debug\...).
```

The placeholder pages print the ABI version returned by `fg_abi_version()` so
the P/Invoke wiring is visible without opening a debugger.

## Layout

| Path | Purpose |
| --- | --- |
| `App.xaml(.cs)` | startup, core snapshot, theme, shared control styles |
| `MainWindow.xaml(.cs)` | TitleBar, NavigationView, page navigation, notice InfoBar, dialogs, badges, taskbar progress |
| `Core/` | fg-ffi P/Invoke bridge (`CoreBridge`), JSON wire models (`CoreWire`), settings and error payloads |
| `App/` | `LauncherModel` (macOS `AppModel` equivalent), section metadata |
| `Models/` | presentation rows (instances, tasks, versions), formatting, loader families |
| `Features/Home/` | 主页 |
| `Features/Library/` | 实例库 with grouped list and preview |
| `Features/Downloads/` | 下载 page and the install dialog |
| `Features/Instances/` | 游戏设置 dialog |
| `Features/Placeholder/` | pages owned by later milestones |
| `Platform/` | AppWindow sizing, DWM chrome, native pickers, taskbar progress |
| `Assets/` | imagery exported from the pen file and the app icon |

## Rules

- No WebView; no HTML/CSS.
- Use WinUI 3 controls; do not reimplement native controls or caption buttons
  in XAML.
- Colours and type come from WinUI theme resources (and `design/tokens.json`
  when a value is not part of the Fluent ramp); the accent is the user's system
  accent.
- `SymbolIcon` has no `FontSize` (it derives from `IconElement :
  FrameworkElement`, not `Control`). To size one, wrap it in a `Viewbox` with
  the target `Width`/`Height`; `FontIcon` is the alternative when the glyph is
  a literal.
- UI code is English; user-visible strings stay in Simplified Chinese for now.
