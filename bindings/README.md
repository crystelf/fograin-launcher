# Native bindings

One Rust core, one Compose Multiplatform UI (Android + desktop). This folder
owns the boundary between them.

## Contract

- Rust crates expose domain operations through `fg-ffi`; UI code never links
  `fg-*` crates directly.
- `fg-ffi` keeps a stable C ABI for the CLI, tests and future native hosts.
- The Kotlin UI (Android and desktop JVM) calls the same JSON payloads through
  the JNI shim in `bindings/fg-ffi-jni` (`libfg_ffi_jni.so` / `.dylib` / `.dll`);
  no UniFFI code generation is required.
- Long-running operations return an opaque task id and push progress through an
  event queue; no UI call blocks on a network or install operation.
- Errors cross the boundary as `{ kind, messageKey, detail }`. The UI maps
  `messageKey` to localized copy.
- The JNI package/object names are part of the ABI: `com.fograin.launcher.platform.CoreNative`
  must not be renamed, or the Rust `Java_*` exports stop resolving.

## Current state

`fg-ffi` exports:

- `fg_abi_version()` — C ABI version.
- `fg_set_data_root(path)` — installs the process data root before the service
  exists. Desktop clients normally leave this to `dirs`; Android passes its
  app-private external files directory before the first RPC call.
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

### RPC methods implemented (M4/M5)

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
| `loaders.versions` | `{kind, minecraft, limit?}` → `{versions:[{version, stable, label?, note?}]}`; `kind` is `fabric`, `quilt`, `forge`, `neoforge`, `optifine` |
| `versions.news` | `{limit, refresh}` → latest releases |
| `version.plan` | `{version, loaderKind?, loaderVersion?}` → files/totalBytes/javaMajor preview, plus `loaderKind` / `loaderVersion` |
| `install.start` | `{version, instanceId?, instanceName?, loaderKind?, loaderVersion?}` → `{taskId, versionId}`; creates the instance when missing or reuses an existing one with the same Minecraft version (so 开始游戏 can complete an incomplete instance); pushes `task.*` events. Forge/NeoForge/OptiFine require a discovered Java runtime before the task is queued |
| `tasks.list` / `tasks.cancel` | `{}` / `{taskId}` |
| `launch.start` | `{instanceId, username?}` → `{pid}`; needs installed files + matching Java |
| `launch.status` / `session.wait` / `session.stop` | session polling and stop |
| `game.log.recent` | `{instanceId, limit}` → last buffered log lines |
| `gameConfig.get` / `.set` | `[game]` read / merge-write |

Loader installs write `shared/versions/<versionId>/<versionId>.json` and record
that id in `instance.toml [loader] version_id`; vanilla instances leave it empty
and resolve `minecraft` directly. `instances.verify` and `launch.start` both use
the effective id. The loader planning/execution contract is documented in
[../docs/m5.md](../docs/m5.md).

Errors always keep the shape `{kind, messageKey, detail}` and camelCase keys
throughout.

All strings returned by the C ABI are owned by the caller and released with
`fg_string_free`. The JNI shim allocates JVM strings instead, so Kotlin never
sees a raw pointer.

### JVM JNI shim

`bindings/fg-ffi-jni` is a small `cdylib` that forwards to the safe JSON
functions in `fg-ffi`. Kotlin loads it as `fg_ffi_jni` on both Android and the
desktop JVM:

```text
com.fograin.launcher.platform.CoreNative
  nativeAbiVersion()      -> Int
  nativeSetDataRoot(path) -> String
  nativeSettingsSnapshot()-> String
  nativeSetTheme(theme)   -> String
  nativeServiceCurrent()  -> String
  nativeRpcCall(request)  -> String
  nativeEventsNext()      -> String
```

Every function catches Rust panics before returning to the JVM. Build the
library for Android with:

```bash
./scripts/build-android.sh              # arm64-v8a + x86_64, release
./scripts/build-android.sh --abi arm64-v8a
```

The script stages `libfg_ffi_jni.so` under
`apps/compose/android/src/main/jniLibs/<abi>/`; Gradle packages it into the APK.

For desktop the same crate is built for the host:

```bash
./scripts/build-jni-desktop.sh          # macOS / Windows / Linux host
```

The desktop library is staged under
`apps/compose/desktop/src/main/resources/native/<os>-<arch>/` and extracted to
a cache directory at runtime before `System.load` (jpackage does not put JAR
resources on `java.library.path`).

The Android client calls `fg_set_data_root` with `getExternalFilesDir(null)`
(falling back to `filesDir`) because Android apps have no usable `$HOME` for the
`dirs` crate. Desktop leaves the data root to the core (`dirs`), unless
`FOGRAIN_HOME` is set.
