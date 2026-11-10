# Fograin Android

Native Android client: Kotlin + Jetpack Compose + Material 3. A separate app
from the desktop clients; it does not use a WebView for launcher screens.

## M4 status

The Android client is wired to the Rust core through JNI and follows the macOS
flows with native Material 3 controls:

- `bindings/fg-ffi-jni` exposes the same JSON contract as the C ABI; Kotlin
  loads `libfg_ffi_jni.so` and installs the app-private data root before the
  first RPC call.
- `AppModel` mirrors the macOS `AppModel`: `instances.list`, `app.snapshot`,
  `gameConfig.get`, `tasks.list`, the 250 ms event pump and the
  notice/alert channels.
- Home: current-instance hero with play/stop, instance filter (最近 / 收藏 /
  全部), task queue, runtime summary and version news.
- Library: native search field, loader filters (全部 / 收藏 / Fabric / Forge /
  NeoForge / 原版), sort menu, grouped rows, preview sheet and the full
  instance settings sheet (`instances.settings.get/set`).
- Downloads: category tabs, a top-bar channel dropdown (正式版 / 快照 /
  愚人节 / 远古版本) to the left of the search icon, a search field matching
  the library page, and a three-dot menu with the reserved 下载设置 entry.
  `version.plan` preview and the install sheet (loader / version / name) follow
  the macOS flow; `install.start` drives the task queue and `task.finished`
  opens the new instance's 游戏设置.
- Settings: theme through `launcher.toml` (`fg_settings_set_theme`), Android-only
  dynamic color, core/ABI/build info and milestone rows. The data-directory
  section is intentionally absent.
- Import `.minecraft` registers an existing directory without moving files. The
  in-app picker walks the app data root because Android's scoped storage cannot
  hand a SAF tree to the Rust core as a filesystem path.
- Instance directory actions copy the path to the clipboard ("复制实例目录路径");
  Android has no Finder equivalent for app-private storage.
- Launch is wired through `launch.start` / `session.stop` and consumes
  `game.started`, `game.line`, `game.windowReady` and `game.exited`; without a
  bundled Java runtime it reports `error.java.none_installed` (M6).

Verified on the `fograin-m1` emulator: version manifest loads, 26.3 installs
through the UI (5216 files / 610.6 MB), the task queue shows progress and the
install-complete flow opens the instance settings sheet.

Not wired yet: bundled JRE and `:game` process/GameActivity (§11), account
management (M7), content search (M8/M9), notifications, Keystore and the
remaining pen screens.

## Build

Requirements: Android SDK 35, NDK r27+, JDK 17 or 21 (Gradle 8.9 does not
support JDK 25), and the Rust Android targets.

```bash
rustup target add aarch64-linux-android x86_64-linux-android
android sdk install ndk/27.2.12479018   # or any NDK r27+

# Build the JNI library and stage it in app/src/main/jniLibs/<abi>/
./scripts/build-android.sh

# Build the APK (JAVA_HOME must point at JDK 17/21)
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
ANDROID_HOME=$HOME/Library/Android/sdk \
./apps/android/gradlew -p apps/android assembleDebug
```

`scripts/build-android.sh` defaults to `arm64-v8a` and `x86_64`, release
profile, API 26. Use `--abi arm64-v8a` for a single device ABI or `--debug` for
a faster build. The staged `jniLibs/` directory is generated and git-ignored.

Unit tests for the wire models, filtering and formatting:

```bash
./apps/android/gradlew -p apps/android testDebugUnitTest
```

## Layout

```text
app/src/main/java/com/fograin/launcher/
├─ MainActivity.kt          # edge-to-edge, first-frame theme, Compose entry
├─ app/                     # AppModel, AppUiState, notices and download state
├─ generated/Tokens.kt      # generated from design/tokens.json, do not edit
├─ model/                   # Instance, loaders, filters, formatting, fixtures
├─ platform/                # CoreNative (JNI) + CoreBridge/CoreWire/CoreEvents
├─ settings/                # Android-only dynamic color; theme lives in launcher.toml
├─ ui/                      # native shell, home, library, downloads, settings
└─ res/                     # strings, themes, launcher icon, hero artwork
```

## Design tokens

`python3 scripts/generate_tokens.py` regenerates `Tokens.kt` and the window
background colors. Do not hand-edit hex values in Kotlin.

## Next

- M6: bundled Java runtime and automatic download; then `launch.start` can
  reach a real game process on Android.
- §11: `:game` process/GameActivity, renderer selection and lifecycle handoff.
- M7/M8/M9: accounts, content search and world management.
