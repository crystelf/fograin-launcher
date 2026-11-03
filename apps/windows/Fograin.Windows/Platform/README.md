Win32/WinUI interop lives here:

- `WindowSizing.cs` — `AppWindow` client-size resize, monitor centering and the
  tall caption buttons.
- `WindowChrome.cs` — DWM immersive dark mode so Mica and the caption buttons
  follow the content theme.
- `PickerActions.cs` — `FileOpenPicker` / `FolderPicker` initialized with the
  window HWND, plus the result dialog used by the M1 prototype.

Mica itself, the WinUI `TitleBar` control and Snap Layouts are configured
declaratively and through the WinUI APIs instead of custom HWND chrome.
