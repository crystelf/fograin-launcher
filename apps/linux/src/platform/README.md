GTK/Adwaita platform adapters live here.

- `theme.rs` reads `appearance.theme` from `launcher.toml` through `fg-core`,
  applies it to `Adw.StyleManager` before the first frame, and writes changes
  back with the core's atomic config writer.
- `files.rs` wraps the native `Gtk.FileDialog` (XDG portal on Linux) and
  `Gio.AppInfo` for opening directories.

libsecret/keyring, Unity LauncherEntry, notifications and desktop packaging
land with M7–M10.
