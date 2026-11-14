# 发布流程

## 版本号

版本只有一处来源：`apps/compose/gradle.properties`

```properties
fograin.version=0.1.0          # 应用版本：Android versionName、桌面 About
fograin.packageVersion=1.0.0   # 安装包版本：jpackage 要求主版本号 ≥ 1
```

发版时同时改这两行（应用到了 1.0 之后两者可以合并）。Android 的 `versionCode`
由 `fograin.version` 推导（`major*10000 + minor*100 + patch`），不需要手改。

## 打 tag

```bash
git tag -a v0.1.0 -m "0.1.0"
git push origin v0.1.0
```

`v*` tag 会触发 `.github/workflows/release.yml`：

| job | 产物 |
| --- | --- |
| `desktop`（macos-15 / windows-2025 / ubuntu-24.04 矩阵） | `Fograin-1.0.0.dmg` / `.msi` / `.deb` |
| `android`（ubuntu-24.04） | `android-release-unsigned.apk` |
| `publish` | 汇总到 GitHub Release（自动生成 release notes） |

jpackage 不能交叉编译，所以三个 OS 各自在自己的 runner 上打包。产物默认**未签名**。

## 签名与公证（待接入密钥后启用）

macOS：

```bash
# CI secrets: APPLE_ID / APPLE_TEAM_ID / APPLE_APP_PASSWORD / MACOS_CERT_P12
codesign --deep --force --options runtime \
  --sign "Developer ID Application: <Team>" "Fograin.app"
xcrun notarytool submit Fograin.dmg \
  --apple-id "$APPLE_ID" --team-id "$APPLE_TEAM_ID" \
  --password "$APPLE_APP_PASSWORD" --wait
xcrun stapler staple Fograin.dmg
```

注意 dylib 要一并签名（`NativeLoader` 运行时从 classpath 解压到临时目录）；
签名顺序是先签 `libfg_ffi_jni.dylib`，再签 app bundle。

Windows：给 `msi` 签 `signtool sign /fd SHA256 /tr <timestamp> /td SHA256`，
SmartScreen 信誉需要时间累积。

Linux：DEB 不做签名，仓库层面用 `apt` 的 `InRelease` 签名。

## 本地出包（验证用）

```bash
./scripts/build-jni-desktop.sh
./apps/compose/gradlew -p apps/compose :desktop:packageDistributionForCurrentOS
# 产物：apps/compose/desktop/build/compose/binaries/main/<dmg|msi|deb>/
```

## 已知缺口

- 自动更新（签名清单 + 增量下载）尚未实现，见 `docs/backlog.md`。
- macOS universal（arm64 + x86_64）包尚未构建；当前只出当前架构。
- 安装包体积未做 jlink 裁剪，约 70–90 MB。
