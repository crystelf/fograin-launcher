# Fograin Android

Native Android client: Kotlin + Jetpack Compose + Material 3. A separate app
from the desktop clients; it does not use a WebView for launcher screens.

## M1 status

The native shell is in place:

- `Scaffold` + Material 3 `NavigationBar` with 主页 / 实例 / 下载 / 设置.
- Home and instance library pages with static fixtures that mirror the pen and
  the macOS client. Rows, filter chips, sort menu, search field, switch menu,
  badge, snackbar and FAB are all native Material 3 controls.
- `LargeTopAppBar` per destination, edge-to-edge insets, predictive back
  (`BackHandler`: search closes first, then the tab returns to 主页).
- Material 3 color schemes, typography and shapes generated from
  `design/tokens.json`; optional Material You dynamic color on Android 12+.
- Appearance (`跟随系统 / 浅色 / 深色`) is applied before the first frame and
  persisted locally until `fg-ffi` exposes `settings_get`/`settings_set` (M4).
- Downloads and remaining pages are honest placeholders until their milestone
  backend exists; no fake progress rows.

Not wired yet: UniFFI/JNI core bridge, launch/GameActivity (`:game` process),
SAF, Keystore, notifications and the remaining pen screens. See
`docs/design.md` §11 and M1.

## Build

Open `apps/android` in Android Studio, or with Android SDK 35 and JDK 17+:

```bash
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew -p apps/android assembleDebug
```

Unit tests for the pure instance filtering/formatting logic:

```bash
./gradlew -p apps/android testDebugUnitTest
```

## Layout

```text
app/src/main/java/com/fograin/launcher/
├─ MainActivity.kt          # edge-to-edge, first-frame theme, Compose entry
├─ generated/Tokens.kt      # generated from design/tokens.json, do not edit
├─ model/                   # Instance, loaders, fixtures, filters, formatting
├─ settings/                # local stand-in for launcher.toml appearance keys
├─ ui/                      # native shell, theme, home, library, settings
└─ platform/                # SAF, Keystore, notifications, :game bridge (later)
```

## Design tokens

`python3 scripts/generate_tokens.py` regenerates `Tokens.kt` and the window
background colors. Do not hand-edit hex values in Kotlin.

## Next

- M4: replace `SampleData` with `instance_list` and wire instance rows.
- M3: launch button and log/exit handling through the core.
- §11: `:game` process/GameActivity, built-in JRE and renderer selection.
