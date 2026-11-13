# Compose client

One Compose Multiplatform UI for Android, Windows, macOS and Linux. The Rust
core and the JSON RPC contract are unchanged; this project owns the UI and the
platform shells. R1/R2 moved the complete M4 surface (home, library, downloads,
settings, instance settings, install sheet and the two inspector sheets) into
`shared/src/commonMain`.

## Layout

```text
shared/
  commonMain/   models, AppModel, 9 platform interfaces, theme, pages, sheets
                composeResources/ (strings + hero image)
  jvmMain/      JNI client, kotlinx.serialization wire models, JVM helpers
  androidMain/  AndroidPlatform: Activity back, clipboard, SharedPreferences,
                device info, dynamic colour, system bars, System.loadLibrary
  desktopMain/  DesktopPlatform: AWT clipboard, java.util.prefs, File browser,
                classpath native loading, no-op back/system bars
android/        Android application shell (Activity, manifest, res, jniLibs)
desktop/        JVM application shell (main, window, jpackage)
```

`commonMain` contains no `android.*` or `java.*` imports. Platform behaviour is
expressed through the nine interfaces in
`commonMain/kotlin/com/fograin/launcher/platform/PlatformServices.kt`:

| # | Interface | Android | Desktop |
| --- | --- | --- | --- |
| 1 | `NativeLibrary` | `System.loadLibrary` | classpath resource + `System.load` |
| 2 | `CoreClientFactory` | shared JNI client | shared JNI client |
| 3 | `DeviceInfoProvider` | `ActivityManager` / `Build` | OS bean / `os.arch` |
| 4 | `ClipboardService` | `ClipboardManager` | AWT clipboard |
| 5 | `LocalPreferences` | `SharedPreferences` | `java.util.prefs` |
| 6 | `DirectoryBrowser` | `java.io.File` over the data root | `java.io.File` |
| 7 | `AppLogger` | `android.util.Log` | stdout |
| 8 | `SystemAppearance` | Material You + bar icon contrast | Fograin palette, no-op bars |
| 9 | `BackDispatcher` | `androidx.activity.compose.BackHandler` | no-op until R4 |

The JSON layer uses `kotlinx.serialization` everywhere; `org.json` is gone from
the shared client.

## Requirements

- JDK 21 (`JAVA_HOME`); Gradle is provided by the wrapper (9.3.1).
- Android: SDK platform 37, build-tools 37.0.0, NDK 27.2 (only for Android builds).
- Rust 1.90+ with the host target and, for Android, `aarch64-linux-android` +
  `x86_64-linux-android`.

## Run the desktop shell

```bash
./scripts/build-jni-desktop.sh
./scripts/run-desktop.sh
```

`build-jni-desktop.sh` builds `bindings/fg-ffi-jni` for the host and stages it
under `desktop/src/main/resources/native/<os>-<arch>/`. The loader extracts it
to a temp directory and calls `System.load`; no `java.library.path` setup is
needed.

## Test

```bash
./scripts/build-jni-desktop.sh

# Pure commonMain tests (models, formatting, wire parsing)
./apps/compose/gradlew -p apps/compose :shared:desktopTest

# JNI smoke tests + off-screen render of all four pages and three sheets
./apps/compose/gradlew -p apps/compose :desktop:test
```

`CoreClientSmokeTest` creates an instance in a temporary data root through the
real JNI service and asserts `instances.list` returns it. `ScreensRenderTest`
renders the four destinations and the three sheets with a deterministic fake
core and writes the PNGs to the temp directory (paths are printed).

## Android

```bash
./scripts/build-android.sh
./apps/compose/gradlew -p apps/compose :android:assembleDebug
```

The Android shell installs its application context with
`AndroidPlatform.install(this)` and passes `getExternalFilesDir(null)` as the
data root before the first RPC, because Android has no usable `$HOME` for the
`dirs` crate. R3 migrates the manifest/res/jniLibs/Activity fully and retires
the legacy module in R6.

## Package the desktop app

```bash
./apps/compose/gradlew -p apps/compose :desktop:packageDistributionForCurrentOS
```

jpackage runs on the target OS only; CI builds DMG (macOS), MSI (Windows, WiX)
and DEB (Linux) on their own runners.
