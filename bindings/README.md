# Native bindings

One Rust core, four native UI clients. This folder owns the boundary between
them.

## Contract

- Rust crates expose domain operations through `fg-ffi`; UI clients never link
  `fg-*` crates directly except on Linux, where GTK and the core are both Rust
  (the Linux client may depend on the crates directly to avoid an FFI hop).
- Swift and Kotlin use UniFFI-generated bindings.
- C# uses a stable C ABI with P/Invoke (generated with `csbindgen` once the
  command surface exists).
- Long-running operations return an opaque task id and push progress through a
  callback/event sink; no UI client blocks on a network or install call.
- Errors cross the boundary as `{ kind, messageKey, detail }`. UI clients map
  `messageKey` to localized copy.

## Current state

`fg-ffi` exports:

- `fg_abi_version()` — C ABI version.
- `fg_settings_snapshot()` — JSON settings snapshot (`abiVersion`, `appVersion`,
  `platform`, `theme`, `dataRoot`, `configPath`, optional `configError`).
- `fg_settings_set_theme(theme)` — writes `appearance.theme` through the Rust
  config layer and returns a JSON result (`ok`, `theme`, `snapshot`, `error`).
- `fg_service_current()` — builds the async launcher service once per process.
- `fg_rpc_call(request)` — dispatches `{"method": "...", "params": {...}}` and
  returns `{"ok": true, ...}` or `{"ok": false, "error": {...}}`.
- `fg_events_next()` — pops up to 64 queued events
  (`task.progress`, `task.finished`, `game.started`, `game.windowReady`,
  `game.line`, `game.exited`) as a JSON array.
- `fg_string_free(value)` — releases strings returned by the core.

### RPC methods implemented (M4)

| Method | Params → Result |
| --- | --- |
| `app.snapshot` | `{}` → platform, appVersion, dataRoot, javaCount, instanceCount, diskFreeBytes, totalMemoryBytes, activeTasks |
| `instances.list` / `instances.summary` | `{}` → `[InstanceRow]` / recent + favorites + totals |
| `instances.create` | `{id, name, minecraft, loaderKind?}` |
| `instances.import` | `{directory, name?}` — registers an external `.minecraft` |
| `instances.rename` / `instances.favorite` | `{id, name}` / `{id, favorite}` |
| `instances.delete` | `{id}` (refused while the instance runs) |
| `instances.settings.get` / `.set` | `{id}` → effective + overrides / `{id, updates:[{setting, value}]}`; `null` value inherits `[game]` |
| `instances.verify` | `{id}` → resource completeness report |
| `instances.dir` | `{id}` → gameDir |
| `versions.list` | `{kind, limit, refresh}` → manifest entries; `kind` is `release`, `snapshot`, `april_fools`, `old`, or empty for all |
| `versions.news` | `{limit, refresh}` → latest releases |
| `version.plan` | `{version}` → files/totalBytes/javaMajor preview |
| `install.start` | `{version, instanceId?, instanceName?}` → `{taskId}`; creates the instance when missing or reuses an existing one with the same Minecraft version (so 开始游戏 can complete an incomplete instance); pushes `task.*` events |
| `tasks.list` / `tasks.cancel` | `{}` / `{taskId}` |
| `launch.start` | `{instanceId, username?}` → `{pid}`; needs installed files + matching Java |
| `launch.status` / `session.wait` / `session.stop` | session polling and stop |
| `game.log.recent` | `{instanceId, limit}` → last buffered log lines |
| `gameConfig.get` / `.set` | `[game]` read / merge-write |

Errors always keep the shape `{kind, messageKey, detail}` and camelCase keys
throughout.

All strings are owned by the caller and released with `fg_string_free`. The real
command surface grows after the four shells are approved. Generated bindings
will live next to each client:

```text
apps/macos/Sources/Fograin/Generated/
apps/windows/Fograin.Windows/Generated/
apps/android/app/src/main/java/com/fograin/launcher/generated/
```

Do not hand-edit generated files.
