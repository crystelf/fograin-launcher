# Compose client

One Compose Multiplatform UI for Android, Windows, macOS and Linux. The Rust
core and the JSON RPC contract are unchanged; this project owns only the UI and
the platform shells. The shared UI currently contains the FFI smoke screen; the
four destinations are being moved in from the legacy Android client.

```text
shared/    KMP library: commonMain (Compose UI) + jvmMain + androidMain + desktopMain
android/   Android application shell (Activity, manifest, res, jniLibs)
desktop/   JVM application shell (main, window, jpackage)
```

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
./apps/compose/gradlew -p apps/compose :desktop:test
```

The smoke test creates a temporary data root, loads the JNI library and calls
`app.snapshot` through the Rust service.

## Android

```bash
./scripts/build-android.sh
./apps/compose/gradlew -p apps/compose :android:assembleDebug
```

The Android shell passes `getExternalFilesDir(null)` as the data root before the
first RPC, because Android has no usable `$HOME` for the `dirs` crate.

## Package the desktop app

```bash
./apps/compose/gradlew -p apps/compose :desktop:packageDistributionForCurrentOS
```

jpackage runs on the target OS only; CI builds DMG (macOS), MSI (Windows, WiX)
and DEB (Linux) on their own runners.
