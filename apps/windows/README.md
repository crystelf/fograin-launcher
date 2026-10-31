# Fograin Windows

Native Windows 11 client: C# + WinUI 3 (Windows App SDK), Mica system
backdrop, native NavigationView and window controls.

## Build

Requires Windows 10 1809+ and the .NET 8 SDK.

```powershell
dotnet build apps/windows/Fograin.Windows/Fograin.Windows.csproj -c Debug
```

The project is unpackaged (`WindowsPackageType=None`) with a self-contained
Windows App SDK for now; MSIX packaging is a later release step.

## Rules

- No WebView; no HTML/CSS.
- Use WinUI 3 controls and Fluent icons; do not reimplement native caption
  buttons in XAML.
- Read colors and metrics from `design/tokens.json` through generated constants.
