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

### Release 文案

每个版本的正文放在 `release-notes/vX.Y.Z.md`，文件名必须和 Git 标签完全一致，
包括 `v`。例如 `v0.2.0` 对应 `release-notes/v0.2.0.md`。

- 有对应文件：文件内容就是完整的 Release 正文；
- 没有对应文件：Release 正文为空，**不会**回退到默认模板，也**不会**自动追加
  GitHub 生成的提交列表；
- 标题、产物附件和 `SHA256SUMS.txt` 仍由工作流自动处理。

从 `release-notes/TEMPLATE.md` 复制一份即可开始写：

```bash
cp release-notes/TEMPLATE.md release-notes/v0.2.0.md
# 编辑 release-notes/v0.2.0.md
git add release-notes/v0.2.0.md
git commit -m "docs(release): 0.2.0 发布文案"
```

如果标签尚未推送，可以改完文案后重新打标签；已经推送的标签不要重写，直接编辑
GitHub Release 的正文，或发布下一个 patch 版本。

只检查版本、不构建：

```bash
./scripts/version.sh current
./scripts/version.sh check
./scripts/version.sh check v0.2.0   # 标签必须与清单中的版本一致
```

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
