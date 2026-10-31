# Fograin Android

Native Android client: Kotlin + Jetpack Compose + Material 3. This is a
separate app from the desktop clients; it does not use a WebView for launcher
screens.

## Build

Open `apps/android` in Android Studio, or from a machine with Android SDK and
JDK 17:

```bash
./gradlew -p apps/android assembleDebug
```

## Next

- Add the `:game` process/GameActivity from design §11.
- Generate Kotlin bindings for the shared Rust core (UniFFI).
- Replace the placeholder bottom navigation with the 15 pen screens.
