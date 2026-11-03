Android platform adapters live here: SAF, Keystore, notifications, connectivity
and the bridge to the separate `:game` process.

M1 only needs the appearance stand-in (`settings/AppearancePreferences.kt`) so
the first frame is themed before the Rust core is reachable. The adapters below
land with their milestones:

- SAF import/export and instance directory — M4/M9
- Keystore-backed account tokens — M7
- Connectivity manager to pause downloads on metered networks — M2
- Foreground service + `NotificationChannel` for installs and game sessions — M3
- `GameActivity` launch description and exit/log hand-off — §11 A4
