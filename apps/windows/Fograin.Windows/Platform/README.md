Win32/WinUI interop lives here:

- `WindowSizing.cs` — `AppWindow` client-size resize, monitor centering and the
  tall caption buttons.
- `WindowChrome.cs` — DWM immersive dark mode so Mica and the caption buttons
  follow the content theme.
- `PickerActions.cs` — `FileOpenPicker` / `FolderPicker` initialized with the
  window HWND. 游戏目录 feeds `instances.import`; the modpack picker keeps its
  M9 message.
- `TaskbarProgress.cs` — native `ITaskbarList3` progress for the download queue
  (the Windows equivalent of the macOS Dock badge).

Mica itself, the WinUI `TitleBar` control and Snap Layouts are configured
declaratively and through the WinUI APIs instead of custom HWND chrome.
