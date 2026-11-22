# 发布与签名

本文说明雾雨启动器的版本号、标签发布流程和各平台签名需要准备什么。

## 版本号：Git 标签是唯一来源

版本只写在三个地方，并由 `scripts/version.sh` 统一维护：

| 位置 | 字段 | 用途 |
| --- | --- | --- |
| `Cargo.toml` | `[workspace.package].version` | Rust 核心里 `env!("CARGO_PKG_VERSION")` 的版本 |
| `Cargo.lock` | 各 `fg-*` 包 | 与 `Cargo.toml` 保持一致 |
| `apps/compose/gradle.properties` | `fograin.version` | Android `versionName`、桌面应用版本 |
| `apps/compose/gradle.properties` | `fograin.packageVersion` | jpackage/MSI/DMG 的安装包版本 |

发布版本使用 `vMAJOR.MINOR.PATCH` 标签，例如 `v0.1.0`。预发布可以使用
`v0.2.0-rc.1`，工作流会把它标记为 GitHub prerelease。

`fograin.packageVersion` 由脚本计算：jpackage 不接受主版本为 0 的版本，所以在
1.0 之前安装包使用 `1.MINOR.PATCH`。例如应用 `0.1.0` 对应安装包 `1.1.0`，
应用 `0.2.0` 对应安装包 `1.2.0`，这样新的安装包仍然可以升级旧的安装包。
预发布标签（如 `v0.2.0-rc.1`）的安装包版本也是 `1.2.0`，与最终版相同；
如果同时测试预发布与正式版，请先卸载再安装。

### 日常发布

```bash
# 1. 改版本号（同时更新 Cargo.lock 与安装包版本）
./scripts/version.sh set 0.2.0

# 2. 提交版本变更
git add Cargo.toml Cargo.lock apps/compose/gradle.properties
git commit -m "chore(release): 0.2.0"
git push origin main

# 3. 在干净的工作树上创建并推送标签
./scripts/version.sh tag --push
```

`tag` 会先运行 `version.sh check`，并拒绝在仍有已跟踪文件改动时打标签。推送
`v*` 标签后，`.github/workflows/release.yml` 会：

1. 通过可复用的 `ci.yml` 跑完整的 CI（版本一致性、Rust、三平台 Compose 测试、Android 构建）；
2. 在 macOS / Windows / Linux 三个 runner 上分别打包 `.dmg` / `.msi` / `.deb`；
3. 在 Linux 上构建 Android APK；
4. 生成 `SHA256SUMS.txt`，创建 GitHub Release 并附加全部产物。

`jpackage` 不能跨平台打包，所以每个安装包必须由对应系统的 runner 生成。

只检查版本、不构建：

```bash
./scripts/version.sh current
./scripts/version.sh check
./scripts/version.sh check v0.2.0   # 标签必须与清单中的版本一致
```

## CI 检查

`.github/workflows/ci.yml` 在以下时机运行：

- 推送到 `main`；
- Pull Request；
- Release 工作流通过 `workflow_call` 调用；
- 手动 `workflow_dispatch`。

包含的检查：

- 版本一致性（标签、`Cargo.toml`、`Cargo.lock`、`gradle.properties`）；
- Gradle wrapper 校验；
- `cargo fmt --check`、`cargo clippy --workspace --all-targets -- -D warnings`、`cargo test`；
- macOS / Linux / Windows 的 `:shared:desktopTest` 与 `:desktop:test`；
- Android 的 Rust JNI 构建、`assembleDebug` 与 `assembleRelease`。

## 首个版本发布前的检查清单

- [ ] 在本地跑通 `./scripts/version.sh check v0.1.0`、`cargo fmt --check`、`cargo clippy --workspace --all-targets -- -D warnings`、`cargo test`；
- [ ] 跑通 `:shared:desktopTest :desktop:test` 和 `:android:assembleDebug`；
- [ ] 在 GitHub Secrets 配好至少 `ANDROID_KEYSTORE_*`（否则 APK 不可安装）；
- [ ] 如需免安全提示，配好 macOS 与 Windows 签名/公证 secret；
- [ ] 确认 `main` 的 CI 全绿，工作树干净；
- [ ] `./scripts/version.sh tag --push`，等待 Release 工作流完成；
- [ ] 下载 `SHA256SUMS.txt` 校验至少一个产物；
- [ ] 在发布后的客户端里确认启动时的更新提示（如果发布的是比当前更高的版本）。

## 签名需要什么

签名材料在构建层面都是可选的：没有配置时仍然能出包，但产物是未签名的，
Release 说明里会明确提示。不过 Android APK 要能安装就必须签名；正式分发建议
全部配置。

### Android：发布密钥（必需才能安装）

Android 不接受未签名的 APK。需要创建一个长期保存的签名密钥：

```bash
keytool -genkeypair -v \
  -keystore fograin-release.jks \
  -alias fograin \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype PKCS12
```

> 这个密钥决定应用升级链：以后所有版本必须用同一个密钥签名。请离线备份
> `fograin-release.jks` 和两个密码，不要提交到仓库。

在 GitHub 仓库的 **Settings → Secrets and variables → Actions** 添加：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 fograin-release.jks` 的输出（macOS 用 `base64 -i fograin-release.jks`） |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | `fograin` |
| `ANDROID_KEY_PASSWORD` | key 密码（可与 keystore 密码相同） |

工作流会把 keystore 解码到 runner 的临时目录，并通过
`FOGRAIN_ANDROID_KEYSTORE` / `FOGRAIN_ANDROID_KEYSTORE_PASSWORD` /
`FOGRAIN_ANDROID_KEY_ALIAS` / `FOGRAIN_ANDROID_KEY_PASSWORD` 传给 Gradle。
本地也可以用同一组环境变量签名：

```bash
FOGRAIN_ANDROID_KEYSTORE=/path/to/fograin-release.jks \
FOGRAIN_ANDROID_KEYSTORE_PASSWORD=... \
FOGRAIN_ANDROID_KEY_ALIAS=fograin \
FOGRAIN_ANDROID_KEY_PASSWORD=... \
./apps/compose/gradlew -p apps/compose :android:assembleRelease
```

验证：

```bash
$ANDROID_HOME/build-tools/37.0.0/apksigner verify --print-certs \
  apps/compose/android/build/outputs/apk/release/android-release.apk
```

### macOS：Developer ID 与应用公证

需要 [Apple Developer Program](https://developer.apple.com/programs/)（年费）：
Developer ID Application 证书用于签名，Apple ID + App 专用密码（或 App Store
Connect API Key）用于公证。

1. 在 Apple Developer / Xcode 中创建 **Developer ID Application** 证书；
2. 从 Keychain 导出 `.p12`（设置密码）；
3. 在 GitHub Secrets 中添加：

| Secret | 内容 |
| --- | --- |
| `MACOS_CERT_P12` | `.p12` 的 base64 |
| `MACOS_CERT_PASSWORD` | 导出 `.p12` 时设置的密码 |
| `MACOS_APPLE_ID` | Apple ID 邮箱 |
| `MACOS_APP_PASSWORD` | Apple ID 的 App 专用密码（不是登录密码） |
| `MACOS_TEAM_ID` | 10 位 Team ID |

生成 secret 值（macOS）：

```bash
base64 -i DeveloperID.p12 | pbcopy   # 粘贴到 MACOS_CERT_P12
```

工作流会创建临时 keychain、导入证书，并把签名身份与 keychain 传给 Gradle。
Compose Desktop 的 `macOS { signing { ... } }` 与
`macOS { notarization { ... } }` 会完成 `codesign`、`notarytool submit --wait`
和 `stapler staple`。没有这些 secret 时，DMG 不签名、不公证。

验证：

```bash
codesign --verify --deep --strict --verbose=2 /Applications/Fograin.app
spctl --assess --type execute --verbose /Applications/Fograin.app
xcrun stapler validate /Applications/Fograin.app
```

### Windows：Authenticode 代码签名证书

需要一个 Windows 代码签名证书（OV 或 EV，通常来自 DigiCert / Sectigo 等 CA）。
GitHub Secrets：

| Secret | 内容 |
| --- | --- |
| `WINDOWS_CERT_BASE64` | `.pfx` 的 base64 |
| `WINDOWS_CERT_PASSWORD` | `.pfx` 密码 |

生成 secret 值（Windows PowerShell）：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("codesign.pfx")) | Set-Clipboard
```

工作流在 MSI 生成后用 Windows SDK 的 `signtool` 签名并校验。EV 证书通常存放在
硬件令牌里，无法导出 `.pfx`；这种情况需要 Azure Trusted Signing、DigiCert
KeyLocker 等云签名服务，或在有令牌的 Windows 机器上手动签名。当前签名范围是
MSI 安装包；如果还需要对安装后的 `Fograin.exe` 单独签名，可在 jpackage 生成
app image 后、打包 MSI 前增加一个 `signtool` 步骤。

验证（Windows）：

```powershell
signtool verify /pa /v Fograin-0.1.0-windows-x64.msi
Get-AuthenticodeSignature .\Fograin-0.1.0-windows-x64.msi
```

### Linux

`.deb` 本身没有像 Authenticode 那样的系统级签名。发布工作流会附加
`SHA256SUMS.txt`，安装前可以校验：

```bash
sha256sum -c SHA256SUMS.txt --ignore-missing
```

如果还想要发布者签名，可以额外用 GPG 对 `SHA256SUMS.txt` 签名
（`gpg --detach-sign --armor SHA256SUMS.txt`）或改用 Sigstore/cosign；这不会改变
`apt` 对 `.deb` 的校验行为。

## 未签名产物

以下情况会得到未签名产物：

- 没有配置 Android keystore：APK 文件名带 `-unsigned`，无法直接安装；
- 没有配置 macOS 证书：DMG 未签名、未公证，首次打开需要
  「系统设置 → 隐私与安全性」允许，或执行
  `xattr -dr com.apple.quarantine /Applications/Fograin.app`；
- 没有配置 Windows 证书：MSI 未签名，SmartScreen 会提示「仍要运行」。

未签名产物仍会附上 SHA-256，Release 说明里也会显示警告。正式对外发布前，
建议至少配置 Android keystore 与 macOS 签名/公证。

## 产物命名

```
Fograin-<version>-macos-arm64.dmg
Fograin-<version>-windows-x64.msi
Fograin-<version>-linux-x64.deb
Fograin-<version>-android.apk
Fograin-<version>-android-unsigned.apk   # 未配置 keystore 时
SHA256SUMS.txt
```

macOS 目前由 `macos-15` runner 构建（Apple 芯片）。如果需要 Intel 版，在
`release.yml` 的 `desktop` matrix 中增加 `macos-15-intel` 条目；`Collect the
installer` 步骤会用 `uname -m` 自动把产物命名为 `macos-x64`。

## 回滚

发布后发现严重问题时：

1. 在 GitHub 上把 Release 标记为 prerelease 或删除；
2. 删除错误标签：`git tag -d v0.2.0 && git push origin :refs/tags/v0.2.0`；
3. 修复后发布新的 patch 版本。不要复用已经推送过的标签，否则已下载的更新
   检查会缓存旧内容，用户也无法判断拿到的是哪个构建。