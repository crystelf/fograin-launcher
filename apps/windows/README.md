# Fograin Windows

Native Windows 11 client: C# + WinUI 3 (Windows App SDK), Mica backdrop, the
native WinUI `TitleBar` control and `NavigationView`.

## Status: M1 native shell

Milestone M1 (docs/design.md §14) is a prototype of the native shell. It covers:

- **Window** — native `TitleBar` control with system caption buttons (so
  Windows 11 Snap Layouts work), Tall caption height, Mica backdrop, native
  app icon and a native search box in the title bar.
- **Navigation** — `NavigationView` with the same information architecture as
  the Windows 11 reference frames in `fograin.pen`: 主页 / 实例库 / 服务器,
  the 资源 group (下载 / Java 运行时) and 账户 / 设置 in the footer.
- **Theme** — the Rust core is read through `fg_ffi.dll` before the first
  window is created; `appearance.theme` is applied with
  `Application.RequestedTheme` so the first frame uses the right appearance.
  Without the DLL the shell starts with the system theme and says so on the
  placeholder pages.
- **主页 / 实例库** — implemented with static sample data (`Models/SampleData.cs`).
  Both use native controls only: `ListView`, `SelectorBar`, `ComboBox`,
  `AutoSuggestBox`, `ProgressBar`, `PersonPicture`, `InfoBadge`.
- **File pickers** — 导入整合包 and 游戏目录 open the native
  `FileOpenPicker` / `FolderPicker` through `WinRT.Interop` to prove the HWND
  interop. No import logic yet.
- **Other pages** — native placeholders that state which milestone will fill
  them in.

Not in this milestone: real core data, launching, downloads, accounts,
settings UI, MSIX packaging, UI automation tests.

## Build

Requires Windows 10 1809+ and the .NET 8 SDK. Windows 11 is the M1 target
(Mica, Snap Layouts and the tall title bar need Windows 11).

```powershell
dotnet build apps/windows/Fograin.Windows/Fograin.Windows.csproj -c Debug -p:Platform=x64
```

The project is unpackaged (`WindowsPackageType=None`) with a self-contained
Windows App SDK; MSIX packaging is a later release step.

The shell works without the Rust core, but to exercise the settings snapshot:

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
| `MainWindow.xaml(.cs)` | TitleBar, NavigationView, page navigation |
| `Core/` | fg-ffi P/Invoke bridge and the settings snapshot |
| `Models/` | instance models and M1 sample data |
| `ViewModels/` | home and library view data |
| `Features/Home/` | 主页 |
| `Features/Library/` | 实例库 with grouped list and preview |
| `Features/Placeholder/` | pages owned by later milestones |
| `Platform/` | AppWindow sizing and native pickers |
| `Assets/` | imagery exported from the pen file and the app icon |

## Rules

- No WebView; no HTML/CSS.
- Use WinUI 3 controls; do not reimplement native controls or caption buttons
  in XAML.
- Colors and type come from WinUI theme resources (and `design/tokens.json`
  when a value is not part of the Fluent ramp); the accent is the user's
  system accent.
- UI code is English; user-visible strings stay in Simplified Chinese for now.
