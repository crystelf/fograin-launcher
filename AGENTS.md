# AGENTS.md

Working guide for AI agents and contributors in this repository. Read it before
the first edit: it records the conventions the codebase already follows, and the
rules that keep releases shippable.

- `README.md` — what the launcher is and how users install it.
- `docs/RELEASING.md` — the full versioning, signing and publishing reference.
- `release-notes/TEMPLATE.md` — the starting point for a release body.

---

## 1. Project overview

Fograin Launcher (雾雨启动器) is a Minecraft: Java Edition launcher.

- **Rust owns the logic.** The workspace in `crates/` and `bindings/` handles
  downloads, installation, accounts, Java runtimes, launching, content,
  modpacks, worlds and diagnostics.
- **One Compose Multiplatform UI.** `apps/compose/` renders the same screens on
  Android and on desktop; there is no second native UI to keep in sync.
- **The bridge is JSON.** Kotlin talks to Rust through the JNI shim in
  `bindings/fg-ffi-jni`, which wraps the C ABI in `bindings/fg-ffi`. Every call
  is a JSON envelope, decoded with `kotlinx.serialization`.
- **Targets:** Windows 10+ (x64), macOS (Apple silicon), Linux (x64, GTK 3 +
  OpenGL), Android 8.0+ (API 26, `arm64-v8a` and `x86_64`). No iOS and no web.
- **License:** GPL-3.0-only. New dependencies must be license-compatible.

Data lives under `$FOGRAIN_HOME` when set, otherwise the platform data directory
(`%APPDATA%\Fograin`, `~/Library/Application Support/Fograin`,
`~/.local/share/fograin`, or the app's private directory on Android).

### Golden rules

1. **User experience comes first.** A feature is not done when it works; it is
   done when the flow is understandable, reversible and hard to get wrong.
2. **Rust decides, Kotlin presents.** Do not reimplement core logic in
   `shared/`; add an RPC method instead.
3. **The UI must look intentional.** Use the design tokens and shared
   components. Default Material widgets dropped into a screen are a regression.
4. **Every user-visible string is localized** in all four locales in the same
   change.
5. **Versions have one source:** the Git tag, mirrored by
   `scripts/version.sh`. Never hand-edit a version string.
6. **Never commit secrets, generated artifacts, or local state.** See
   [§6](#6-version-control).
7. **Leave the tree releasable.** `main` and every tag must build and test
   cleanly.

---

## 2. Architecture

| Layer | Location | Responsibility |
| --- | --- | --- |
| Shared UI | `apps/compose/shared/src/commonMain` | Screens, components, theme, `AppModel`/`AppState`, i18n |
| Platform actuals | `shared/src/androidMain`, `shared/src/desktopMain` (and `jvmMain`) | File pickers, native loader, system links, locale |
| Desktop shell | `apps/compose/desktop` | Window chrome, native frame handling, jpackage |
| Android shell | `apps/compose/android` | Activity, manifest, launcher resources, staged `.so` |
| JNI bridge | `bindings/fg-ffi-jni` | `external fun` ↔ Rust, C string ownership |
| C ABI + service | `bindings/fg-ffi` | `Service::rpc` method table, settings snapshot, event queue |
| Core crates | `crates/fg-*` | Everything below the UI |

Data flow: `Composable → AppModel action → CoreClient.rpc → JNI → Service::rpc
→ fg-* crate → JSON envelope → AppState → recomposition`.

The RPC method table in `bindings/fg-ffi/src/service/mod.rs` is part of the ABI:
Kotlin binds to those exact strings. Adding a method means adding the `match`
arm, a typed wrapper in `platform/CoreWire.kt`/`AppModel`, and a test. Renaming
or removing one requires updating both sides in the same commit.

### Crates

| Crate | Purpose |
| --- | --- |
| `fg-core` | Error type, paths, config, platform detection. Must stay free of UI and async-runtime dependencies. |
| `fg-net` | HTTP client, mirror rules, segmented downloads, content-addressed cache |
| `fg-task` | Task graph, progress aggregation, cancellation |
| `fg-game` | Version manifests, version JSON resolution, installers, launch arguments |
| `fg-loader` | Fabric, Quilt, Forge, NeoForge, OptiFine installation |
| `fg-java` | JDK discovery, runtime parsing, managed runtime downloads |
| `fg-auth` | Offline accounts, Yggdrasil / authlib-injector |
| `fg-content` | Local mods/resource packs/shaders, Modrinth and CurseForge |
| `fg-world` | Worlds, data packs, zip backups |
| `fg-diag` | Log collection and the crash-analysis rule engine (`rules/`) |
| `fg-cli` | Headless debug CLI; the fastest way to reproduce core behavior |
| `fg-ffi`, `fg-ffi-jni` | ABI boundary and JNI shim |

---

## 3. Repository layout

```
.
├── Cargo.toml                  # Workspace, shared dependency versions, release profile
├── Cargo.lock                  # Committed; update with cargo, never by hand
├── rust-toolchain.toml         # Pinned toolchain (1.100.0) so local == CI
├── rustfmt.toml                # max_width = 100, edition 2024
├── crates/                     # fg-* libraries and the debug CLI
├── bindings/
│   ├── fg-ffi/                 # C ABI, Service::rpc, event queue
│   └── fg-ffi-jni/             # JNI shim for Android and desktop
├── apps/compose/
│   ├── gradle.properties       # fograin.version / fograin.packageVersion
│   ├── shared/                 # commonMain UI + AppModel, platform actuals
│   ├── desktop/                # Main.kt, WindowChrome, jpackage config
│   └── android/                # MainActivity, manifest, res, jniLibs
├── design/
│   ├── tokens.json             # Source of truth for color, type, spacing, radii
│   └── assets/                 # Artwork used by the UI
├── docs/RELEASING.md
├── release-notes/              # One file per tag; the GitHub Release body
├── scripts/                    # version.sh, JNI/Android builds, token generator
├── .github/workflows/          # ci.yml, release.yml
├── .githooks/pre-push          # Mirrors the Rust CI job
└── temp/                       # Untracked reference checkouts — never edit
```

`temp/` holds upstream launchers used as behavioral reference only. It is
git-ignored: do not copy code from it, and do not "fix" it.

### Generated or staged — do not edit, do not commit

| Path | Produced by |
| --- | --- |
| `apps/compose/shared/src/commonMain/kotlin/.../generated/Tokens.kt` | `python3 scripts/generate_tokens.py` |
| `apps/compose/android/src/main/jniLibs/<abi>/*.so` | `./scripts/build-android.sh` |
| `apps/compose/desktop/src/main/resources/native/<triple>/*` | `./scripts/build-jni-desktop.sh` |
| `target/`, `**/build/`, `**/.gradle/`, `**/.kotlin/` | Cargo / Gradle |

`apps/compose/local.properties` is machine-specific and ignored.

---

## 4. Prerequisites

- **Rust 1.100.0** — `rustup` reads `rust-toolchain.toml`; do not upgrade the
  pin casually, local and CI clippy must run the same compiler.
- **JDK 21** — Gradle pins `jvmToolchain(21)`, so that toolchain must be
  installed regardless of which JDK runs Gradle.
- **Android SDK 37 / build-tools 37.0.0 / NDK 27.2.12479018**, plus the
  `aarch64-linux-android` and `x86_64-linux-android` Rust targets, for Android.
- **Python 3** only for the design-token generator.
- **WiX** (Windows MSI) and **GTK 3** (Linux) are packaging dependencies; local
  packaging builds the host OS format only (`jpackage` cannot cross-compile).
- **GStreamer** (Linux runtime; `libgstreamer1.0-0` plus the base/good plugins)
  backs the community video player. The macOS build extracts a native AVPlayer
  dylib, so the hardened-runtime entitlements must allow library validation
  (or the extracted dylib must be signed).

One-time setup:

```bash
git config core.hooksPath .githooks
rustup target add aarch64-linux-android x86_64-linux-android   # Android only
```

An Android signing key is needed only for an installable release APK; debug
builds and desktop packages need none.

---

## 5. Common commands

### Rust

```bash
cargo fmt --all                              # format
cargo fmt --all -- --check                   # verify
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked

# One test / one crate
cargo test -p fg-net --test engine
cargo test -p fg-game install_launch -- --nocapture
```

`cargo fmt --check`, clippy and the tests are exactly what `.githooks/pre-push`
and CI run. Do not use `#[allow(clippy::…)]` to silence a warning without a
comment explaining why the code must stay as it is.

### Desktop

```bash
./scripts/build-jni-desktop.sh                # --debug for a faster build
./scripts/run-desktop.sh                      # build JNI, then :desktop:run
./apps/compose/gradlew -p apps/compose :desktop:run

# Tests: shared layer + desktop shell (render tests included)
./apps/compose/gradlew -p apps/compose :shared:desktopTest :desktop:test

# Narrow a Compose test
./apps/compose/gradlew -p apps/compose :desktop:test \
  --tests "com.fograin.launcher.desktop.WindowChromeTest"

# Installer for the current OS
./apps/compose/gradlew -p apps/compose :desktop:packageDistributionForCurrentOS
```

Rebuild the JNI library after **any** Rust change. `:desktop:run` loads the
staged copy under `desktop/src/main/resources/native/`, not `target/`, so
skipping this step silently runs stale core code.

### Android

```bash
export ANDROID_HOME=~/Library/Android/sdk      # macOS example; local.properties also works
./scripts/build-android.sh                     # --debug, --abi arm64-v8a, --api 26
./apps/compose/gradlew -p apps/compose :android:assembleDebug
adb install -r apps/compose/android/build/outputs/apk/debug/android-debug.apk
```

### Design tokens

```bash
python3 scripts/generate_tokens.py
```

Regenerate `Tokens.kt` after editing `design/tokens.json`; never edit the
generated Kotlin file.

### Versioning

```bash
./scripts/version.sh current
./scripts/version.sh check                     # or: check v0.3.0
./scripts/version.sh set 0.3.0                 # Cargo.toml, Cargo.lock, gradle.properties
./scripts/version.sh tag --push
```

### Headless core CLI

```bash
cargo run -p fg-cli -- --help
cargo run -p fg-cli -- versions
cargo run -p fg-cli -- install 1.21.1
cargo run -p fg-cli -- launch <instance>
```

The CLI reads the same data root as the launcher (`FOGRAIN_HOME` for an isolated
test directory). Use it to reproduce a core bug without the UI.

---

## 6. Version control

### Branches

- `main` — release-ready history; only receives PR merges from `dev`. Never
  commit directly to `main`.
- `dev` — integration branch for day-to-day work.
- Topic branches — `feat/<topic>`, `fix/<topic>`, short-lived, branched from
  `dev`, merged back by PR.

### Tags and versions

- A release is an annotated tag `vMAJOR.MINOR.PATCH` (pre-releases:
  `v0.2.0-rc.1`, `v0.2.0-beta.2`). A tag containing `-` is published as a
  GitHub prerelease automatically.
- The tagged commit must contain the matching `release-notes/vX.Y.Z.md` and
  agree with `Cargo.toml`, `Cargo.lock` and `apps/compose/gradle.properties`.
  `scripts/version.sh tag` enforces the manifests and refuses a dirty tree.
- **Never rewrite or reuse a pushed tag.** Users cache release lookups by tag.
  For a bad release, publish a new patch version (or delete an unpublished tag
  before it is public).
- The installer version is separate: while the app is on 0.x, jpackage uses
  `1.MINOR.PATCH` (`fograin.packageVersion`). `scripts/version.sh` computes it;
  do not set it manually.

### Repository hygiene

- Commit `Cargo.lock`. Do not commit `target/`, Gradle `build/` output,
  `.gradle/`, `.kotlin/`, `local.properties`, staged native libraries, logs,
  diagnostics bundles, keystores or certificates.
- Do not reformat or reorder unrelated files in a feature commit; it makes the
  diff unreviewable.
- Keep `git status` clean before tagging; the release notes file must be part of
  the tagged commit.
- Do not force-push `main` or `dev`. Do not rewrite commits that are already in
  a tag.

---

## 7. Commit messages

Conventional Commits, one logical change per commit:

```
<type>(<scope>): <subject>

<body: why, constraints, trade-offs>

<footer: BREAKING CHANGE or refs>
```

| Type | Use for |
| --- | --- |
| `feat` | New user-visible capability |
| `fix` | Bug fix |
| `perf` | Performance or memory improvement |
| `refactor` | Restructuring with no behavior change |
| `docs` | README, docs, release notes |
| `test` | Tests and fixtures only |
| `build` | Cargo, Gradle, packaging |
| `ci` | Workflows and hooks |
| `chore` | Versions, formatting, housekeeping |
| `style` | Formatting only, no logic |
| `revert` | Reverting a previous commit |

Rules:

- Type and scope are lowercase; the subject is imperative, specific and has no
  trailing period. Aim for ≤ 72 characters.
- Scope is optional but recommended: `ui`, `app`, `desktop`, `android`, `core`,
  `net`, `task`, `game`, `loader`, `java`, `auth`, `content`, `world`, `diag`,
  `ffi`, `release`, `deps`, `docs`.
- Use the body only when the reason is not obvious; explain **why**, not what
  the diff already shows.
- Breaking changes use `!` (`feat(ffi)!: …`) plus a `BREAKING CHANGE:` footer.
- The subject may be Simplified Chinese (matching history) or English; keep one
  language per commit.
- **No personal information and no attribution.** Do not add names, email
  addresses, handles, co-author trailers, or "generated by" lines to commit
  messages, branch names, PR text or release notes. Git author metadata comes
  from local config and is not repeated in the message.
- Do not commit `WIP`, `fix bug`, `update code` or a mixed bag of unrelated
  changes. If the subject needs three "and"s, split the commit.
- Release commits use the established forms: `chore(release): 0.3.0` and
  `docs(release): 0.3.0`. Commit the release notes before the version bump so
  the tag contains both.

```text
feat(downloads): 添加下载队列与任务暂停继续
fix(desktop): 修复进入全屏后设置分栏位置漂移
perf(net): 复用分片缓存减少重复校验
docs(release): 0.3.0 发布文案
chore(release): 0.3.0
```

---

## 8. UI and UX standards

This is a consumer-facing desktop and mobile app. A feature that works but feels
rough is not finished.

### Design system

- Colors, typography, spacing, radii and palettes come from
  `design/tokens.json` and the generated `FograinTokens`; the theme is applied
  through `FograinTheme` (`ui/theme`). Do not introduce literal hex colors,
  arbitrary `sp` sizes or one-off paddings in screens. If a new value is truly
  needed, add it to the token file and regenerate.
- Reuse the shared components in `ui/components` (`PageHeader`, `SectionHeader`,
  `StatusPill`, `FograinSheet`, `Progress`, `Skeleton`, `SegmentedTabRail`,
  `DropdownField`, page transitions). Building a second variant of an existing
  control is a regression.
- Support light and dark themes; check both. Platform accents (macOS, Windows,
  GNOME) must not break contrast.
- Use the platform font stacks from the tokens: SF Pro / PingFang on macOS,
  Segoe UI Variable / Microsoft YaHei UI on Windows, Adwaita / Noto Sans CJK on
  GNOME, Roboto on Android; monospace for logs, paths and hashes.
- Window chrome is part of the design: macOS traffic lights, Windows caption
  buttons and Snap, Linux native title bar. Do not regress native resize,
  maximize, double-click-to-maximize or fullscreen behavior.

### Motion

- Layout changes use `FograinMotion.Layout` / `LayoutDp` / `LayoutSize` (a
  medium-low spring). Fades stay on short tweens; a spring on alpha can
  undershoot and flicker.
- Page changes use `fograinPageTransition`, full-window overlays use
  `fograinOpaquePageTransition`. Do not invent a third transition.
- Keep motion short (roughly 140–320 ms), interruptible and never blocking
  input. Do not animate every property; animate the change the user needs to
  follow.

### States and feedback

Every asynchronous surface needs all of these, not just the happy path:

- **Loading:** a skeleton or placeholder that matches the final layout, so
  content does not jump when it arrives.
- **Empty:** what this list is for and the primary action to fill it.
- **Error:** a human sentence, the technical detail behind a disclosure, and a
  recovery action (retry, open settings, open logs).
- **Progress:** determinate when the total is known, with bytes/items, speed and
  remaining time; cancellable and resumable where the core supports it.
- **Success:** a brief confirmation, then get out of the way. No modal for a
  success the user can already see.

Destructive actions confirm and name the object. Long operations must never
freeze the window; show that work continues and preserve it when navigating
away. Operations that change files must be idempotent or safely retryable.

### Layout and adaptivity

- One UI serves phones and desktops. The shell switches between the navigation
  rail and the bottom bar at **840 dp**; settings switches between the split
  view and the compact list at **820 dp**. Reuse those breakpoints instead of
  adding new ones.
- Check at least: 360 × 640 (phone), 600 × 900 (tablet/narrow window),
  1120 × 780 (desktop reference used by render tests), and a maximized/ultrawide
  window. Text must not clip or overlap in any of them, in all four locales.
- Use `BoxWithConstraints`, `WindowInsets` and safe-area padding; never assume a
  fixed window size or a visible status bar.
- Preserve navigation state, scroll position and in-progress operations across
  resize, rotation and fold/unfold. A breakpoint crossing must animate, not
  flash.

### Localization and copy

- All strings live in `shared/src/commonMain/composeResources/values*/strings.xml`
  and every key must exist in **all four** files:
  `values` (Simplified Chinese, source of truth), `values-en`, `values-ja`,
  `values-zh-rTW`. Add them in the same commit; a missing key is a bug.
- Use resource placeholders, never string concatenation, for sentences with
  values. Leave room for longer English/Japanese labels.
- Keep terminology consistent with the existing UI (实例, 整合包, 加载器, 资源).
- Errors from the core arrive as a stable `messageKey` plus a technical detail.
  Map new keys in `platform/CoreErrorCopy.kt` (currently Chinese-only by
  design); never show a raw key or a Rust `Debug` string to the user.
- Code comments are English. User-facing copy is Simplified Chinese with the
  three translations above.

### Accessibility

- Icon-only buttons need a `contentDescription`; decorative images use `null`.
- Minimum touch target 48 dp; visible keyboard focus on desktop; logical focus
  order; `Esc` closes the topmost sheet/dialog; Android back goes through the
  shared back dispatcher.
- Do not encode state with color alone — pair it with an icon or text. Keep
  text contrast at least 4.5:1.
- Progress and status changes should be readable by a screen reader; avoid
  announcing on every frame.

### Performance

- No blocking file or network I/O in composition or on the UI thread; core
  calls are `suspend` and already off the main thread.
- `LazyColumn`/`LazyRow` need stable keys; hoist state; avoid reading large
  state objects in every composable.
- Images are decoded at display size; lists of artwork need placeholders and
  should not trigger full-resolution decodes.
- Startup: settings are read before the first frame, the update check is
  asynchronous. Do not add synchronous work to the startup path.
- Keep scrolling at 60 fps and resizing smooth; layout thrash during a window
  drag is a bug.

---

## 9. Code standards

### Rust

- Edition 2024, 100-column rustfmt, clippy-clean with `-D warnings`.
- Errors use `fg_core::{Error, ErrorKind, Result}` with a stable `messageKey`.
  Do not `unwrap`/`expect` on user input, files or network data in library code
  (tests may). No `panic!` for an expected failure.
- Blocking work inside async code goes through `spawn_blocking`; do not hold a
  lock across `.await`.
- New dependencies go into `[workspace.dependencies]` and are used with
  `.workspace = true`; prefer the standard library and existing helpers, and
  justify anything new. Check the license (GPL-3.0-only compatible).
- Paths come from `fg_core::paths`, config from `fg_core::config`, writes use
  `write_atomic`. Do not scatter `dirs::` calls through feature crates.
- Platform quirks get a comment explaining the workaround. On Windows, spawn
  child processes with `CREATE_NO_WINDOW`; a console flash is a user-visible
  bug.
- Public items have doc comments; crate roots have a `//!` overview. Comments
  explain why and the invariants, not what the next line does.

### Kotlin / Compose

- 4-space indent, 120-column limit, trailing commas, explicit imports.
- Put code in `commonMain` unless it genuinely needs a platform API; use
  `expect`/`actual` or a `*Platform` implementation. `jvmMain` is shared by
  Android and desktop.
- State lives in `AppModel` + immutable `AppState`; screens receive state and
  callbacks. Composables must not call `CoreClient.rpc` directly.
- Decode and encode core payloads in `platform/CoreWire.kt`; expose typed
  methods, not `JsonObject`, to the UI.
- Name shared components with the `Fograin` prefix; pages end in `Page`, modal
  containers in `Sheet` or `Dialog`, list items in `Card`/`Row`.
- New screens need a render test in `desktop/src/test` (and UI assertions where
  interaction matters). The render tests write a PNG and print
  `FOGRAIN_RENDER_PNG=<path>`; inspect it for visual changes.

### Cross-cutting

- No hardcoded app version, download URL or JDK path. Route network access
  through `fg-net` so mirrors, retries and caching apply.
- Never log access tokens, passwords or full account payloads. Diagnostics
  exports are redacted; keep them that way.
- Do not copy code from `temp/` references. The launcher is an independent
  implementation; upstream projects are referenced for behavior only.
- Keep changes focused. A bug fix does not include a refactor, a dependency bump
  and a formatting pass.

---

## 10. Release notes

Each release body is a single file, `release-notes/vX.Y.Z.md`, whose name equals
the tag exactly (including the leading `v`). The release workflow copies that
file verbatim into the GitHub Release. If it is missing, the body is **empty** —
there is no template fallback and no generated commit list.

Write for players, not for the team. Only changes, summarized.

### Content rules

1. **Only what changed in this release.** Do not repeat the feature list, do not
   describe the app, do not list unchanged features.
2. **Summary, not detail.** One line per change, 3–8 bullets for a typical
   release. If a bullet needs a second sentence to make sense, it is probably an
   internal change or belongs in docs.
3. **User-visible language.** No file names, module names, function names,
   class names, commit hashes, PR numbers, test counts or "refactored X".
4. **Group and order.** `## 本次更新` with `新增` / `修复` / `优化` bullets,
   most impactful first. Keep the established format.
5. **One intent per bullet.** "优化了多线程下载" is a release note;
   "重写 DownloadEngine 的分片调度并引入有界信号量" is not.
6. **Action required only when true.** If users must reinstall, re-login,
   migrate data or install a new runtime, add a short `## 注意` section.
   Otherwise omit it; do not repeat signing or installation instructions.
7. **No praise, no marketing, no emoji.** No "重大更新", "史诗级", "感谢支持"
   unless there is a concrete, user-relevant reason.
8. **No personal information.** No names, handles, email addresses or
   contributor/agent attribution.
9. **Keep old notes.** A published file is the historical record; do not delete
   it or rewrite a published release body.
10. **Title and intro.** `# 雾雨启动器 X.Y.Z` (pre-release:
    `X.Y.Z RC 1`), followed by one sentence naming the theme of the release.

### Examples

| Too detailed (avoid) | Summary (write this) |
| --- | --- |
| 重构 DownloadEngine，把分片调度从 FIFO 改为按大小排序，并复用内容寻址缓存，基准测试提升 18%。 | 优化了多线程下载，大文件安装更快。 |
| 修复 SettingsSplit 在 macOS 进入全屏时读取了过期的布局状态，导致 divider offset 漂移的问题。 | 修复了启动器在 macOS 全屏后设置分栏位置漂移的问题。 |
| 更新 Compose Multiplatform 到新版并处理 API 变更。 | —（内部变更，不写） |

Good release note:

```markdown
# 雾雨启动器 0.3.0

本版本集中改善下载体验，并修复若干窗口问题。

## 本次更新

- 新增下载任务的暂停与继续。
- 优化了多线程下载，大文件安装更快。
- 修复了启动器在 Windows 上启动时闪现控制台窗口的问题。
- 修复了 macOS 全屏后设置分栏位置漂移的问题。

## 注意

- 本版本更换了安装包签名，升级前请先卸载旧版本。
```

### Publishing order

```bash
# 1. Write release-notes/vX.Y.Z.md from release-notes/TEMPLATE.md
# 2. Commit the notes first so the tag contains them
git add release-notes/vX.Y.Z.md
git commit -m "docs(release): X.Y.Z"

# 3. Bump the manifests through the script, never by hand
./scripts/version.sh set X.Y.Z
git add Cargo.toml Cargo.lock apps/compose/gradle.properties
git commit -m "chore(release): X.Y.Z"

# 4. Tag from a clean tree; --push triggers the Release workflow
./scripts/version.sh tag --push
```

The workflow re-runs CI, builds the installers on macOS / Windows / Linux,
builds the APK, generates `SHA256SUMS.txt`, publishes the release, and treats
any tag containing `-` as a prerelease. Signing setup and secrets are documented
in `docs/RELEASING.md`.

---

## 11. Definition of done

Before handing work back:

- [ ] `cargo fmt --all -- --check`, clippy with `-D warnings`, and
      `cargo test --workspace` pass.
- [ ] `:shared:desktopTest :desktop:test` pass for UI changes; the render PNG
      was inspected.
- [ ] The JNI library was rebuilt after Rust changes.
- [ ] New strings exist in all four locales; new core error keys have copy.
- [ ] Light/dark, narrow/wide and the four locales were checked by hand for
      changed screens.
- [ ] Loading, empty, error and progress states exist for new async surfaces.
- [ ] No new warnings, no hardcoded version, no secret, no generated file
      committed.
- [ ] The diff contains one logical change and the commit message follows
      [§7](#7-commit-messages).
- [ ] For a release: notes exist with the exact tag name, the tree is clean, and
      `./scripts/version.sh check vX.Y.Z` passes.

## 12. Common pitfalls

- **Stale native code.** Editing Rust and running `:desktop:run` without
  `./scripts/build-jni-desktop.sh` uses the previously staged library.
- **Missing translation key.** Adding a string only to `values/strings.xml`
  breaks the other locales.
- **Hand-edited tokens.** `Tokens.kt` is regenerated; changes disappear.
- **Version drift.** Editing `Cargo.toml` without `Cargo.lock` and
  `gradle.properties` fails CI and the tag check.
- **Empty release body.** Tagging without `release-notes/<tag>.md` publishes
  nothing; the file must be inside the tagged commit.
- **UI thread blocking.** Filesystem scans, manifest parsing and zip work belong
  in the core, not in a composable.
- **Breakpoint flash.** New adaptive layouts must use the motion tokens instead
  of switching instantly.
- **Console window flash (Windows).** Spawning a process without
  `CREATE_NO_WINDOW`.
- **Network in tests.** Inject fake sources or use the existing fixtures;
  render tests must not depend on the network.
- **Formatting noise.** Running a formatter over untouched files buries the real
  change.

## 13. Reference

- `README.md` — product overview, install matrix, FAQ.
- `docs/RELEASING.md` — version source of truth, CI, signing, rollback.
- `release-notes/TEMPLATE.md` — release body skeleton.
- `design/tokens.json` — design values; `scripts/generate_tokens.py` produces
  the Kotlin constants.
- `.github/workflows/ci.yml` — the exact checks every change must pass.
- `.github/workflows/release.yml` — artifact naming, notes injection, signing.
- `crates/fg-cli` — reproducible, UI-free reproduction of core behavior.
