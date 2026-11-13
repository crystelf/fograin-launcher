Android platform adapters live here. The JNI bridge is the only path from
Kotlin to the Rust core; the remaining adapters land with their milestones.

M4 wires:

- `CoreNative` — `external` methods implemented by `bindings/fg-ffi-jni`.
- `CoreBridge` — loads the library, installs the app-private data root with
  `fg_set_data_root`, dispatches JSON RPC and drains events.
- `CoreWire` / `CoreEvents` — camelCase models shared with the desktop shell.

Still owned by later milestones:

- SAF import/export beyond the app data root — M9 (the M4 picker walks the
  data root because a SAF tree cannot be passed to the Rust core as a path)
- Keystore-backed account tokens — M7
- Connectivity manager to pause downloads on metered networks — M2
- Foreground service + `NotificationChannel` for installs and game sessions — M3
- `GameActivity` launch description and exit/log hand-off — §11 A4
