# Fograin Linux

Native GNOME client: Rust + GTK4 + libadwaita. It links the shared `fg-*`
crates directly and does not use a WebView or a CSS skin.

## Build

Ubuntu 24.04+ / Fedora:

```bash
sudo apt install libgtk-4-dev libadwaita-1-dev build-essential pkg-config
cargo run --manifest-path apps/linux/Cargo.toml
```

This app is intentionally a separate Cargo workspace so a macOS/Windows
`cargo test --workspace` does not try to compile GTK.

## Rules

- Use libadwaita widgets and symbolic icons.
- Native header bar and window controls; no fake macOS traffic lights.
- Read colors and metrics from `design/tokens.json` through generated constants.
