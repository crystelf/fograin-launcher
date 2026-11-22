# 雾雨启动器 · Fograin Launcher

Minecraft Java 版启动器。Rust 核心负责下载、安装、账户与启动，Compose Multiplatform 提供 Android 与桌面（Windows / macOS / Linux）共用的界面。

[![CI](https://github.com/crystelf/fograin-launcher/actions/workflows/ci.yml/badge.svg)](https://github.com/crystelf/fograin-launcher/actions/workflows/ci.yml)

## 功能

- **版本安装**：正式版、快照、远古版本，共享 `versions/`、`libraries/`、`assets/`。
- **加载器**：Fabric、Quilt、Forge、NeoForge、OptiFine。
- **Java 运行时**：自动发现系统 JDK；缺失时按 Mojang 官方目录下载托管运行时（可关闭）。
- **实例**：独立游戏目录、内存/JVM 参数/分辨率覆盖、导入外部 `.minecraft`。
- **账户**：离线账户、第三方 Yggdrasil / authlib-injector 登录，多角色切换。
- **内容**：模组、资源包、光影、世界的启用/禁用、删除与更新检查；Modrinth 与 CurseForge（经 MCIM）下载。
- **整合包与数据包**：`.mrpack` / CurseForge / HMCL / MultiMC 导入与 `.mrpack` 导出；数据包按世界管理。
- **诊断**：`latest.log`、崩溃报告与 `hs_err` 分析，导出脱敏诊断包。
- **网络**：镜像源自动回退、断点续传、分段下载、内容寻址缓存。
- **更新提示**：启动时异步检查 GitHub Release，右下角提示新版本并跳转到发布页。
- **多语言**：简体中文、繁體中文、English、日本語。

## 下载与安装

从 [Releases](https://github.com/crystelf/fograin-launcher/releases) 获取当前平台的安装包：

| 平台      | 产物     | 说明                                    |
|---------|--------|---------------------------------------|
| Windows | `.msi` | Windows 10 及以上                        |
| macOS   | `.dmg` | Apple 芯片（arm64）                       |
| Linux   | `.deb` | 需要 GTK 3 与 OpenGL                     |
| Android | `.apk` | Android 8.0（API 26）及以上，arm64 与 x86_64 |

**签名状态取决于发布时配置的证书。** 未签名时，macOS 首次打开需要在
「系统设置 → 隐私与安全性」里允许，或执行
`xattr -dr com.apple.quarantine /Applications/Fograin.app`；Windows SmartScreen
需要选择「仍要运行」。Android 未签名 APK 无法直接安装。签名所需的密钥与证书见
[发布与签名](docs/RELEASING.md)。

## 首次运行

- 数据目录（配置、实例、缓存、日志）：

  | 平台      | 路径                                      |
  |---------|-----------------------------------------|
  | Windows | `%APPDATA%\Fograin`                     |
  | macOS   | `~/Library/Application Support/Fograin` |
  | Linux   | `~/.local/share/fograin`                |
  | Android | 应用私有外部目录                                |

  设置环境变量 `FOGRAIN_HOME` 可以改为便携式安装。
- 启动器会扫描本机 JDK；没有合适版本时，安装或启动会按需下载官方运行时。
- 首次启动会创建一个离线账户，可在账户面板里改名或添加第三方登录。

## 已知限制

- 不提供微软正版登录（需要 Azure 应用审核）。
- 不提供皮肤与披风管理；第三方皮肤由服务端与 authlib-injector 在游戏内处理。
- 更新提示只负责检查版本并跳转到 GitHub Release；下载和安装仍需手动完成。
- 未配置签名时为未签名、未公证构建；macOS 目前只发布 Apple 芯片版本。
- 目标平台为 Windows / macOS / Linux 桌面与 Android；不提供 iOS 与 Web 版本。

## 常见问题

**启动时提示找不到 Java**
打开设置 → Java 运行时，下载对应大版本，或开启「自动下载」。旧版本（1.8.9 / 1.12.2 / 1.16.5）需要 Java 8。

**游戏启动后立即退出**
实例设置 → 日志与诊断 会列出最近的崩溃原因和修复建议；也可以直接查看 `.minecraft/logs/latest.log`。

**下载卡住或失败**
设置 → 网络 可以切换下载源（自动 / 官方 / 镜像）并调整并发与限速；已下载的分片会在重试时复用。

**日志在哪里**
`<数据目录>/logs/launcher.log`（启动器本身）与实例目录下的 `logs/latest.log`（游戏）。
需要反馈问题时，实例设置里的「导出诊断包」会打包脱敏后的日志与崩溃报告。

## 从源码构建

需要 Rust 1.100、JDK 21 和（Android）Android SDK 37 / NDK 27。

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace

# 桌面端
./scripts/build-jni-desktop.sh
./apps/compose/gradlew -p apps/compose :desktop:run

# Android
./scripts/build-android.sh
./apps/compose/gradlew -p apps/compose :android:assembleDebug
```

测试：`./apps/compose/gradlew -p apps/compose :shared:desktopTest :desktop:test`。
打包：`./apps/compose/gradlew -p apps/compose :desktop:packageDistributionForCurrentOS`（只能打当前系统的包）。

## 版本与发布

版本号由 Git 标签 `vMAJOR.MINOR.PATCH` 驱动。`scripts/version.sh` 会同步
`Cargo.toml`、`Cargo.lock` 与 `apps/compose/gradle.properties`：

```bash
./scripts/version.sh set 0.2.0
git add Cargo.toml Cargo.lock apps/compose/gradle.properties
git commit -m "chore(release): 0.2.0"
./scripts/version.sh tag --push
```

推送标签后，[Release 工作流](.github/workflows/release.yml)会先跑完整 CI，再在
macOS / Windows / Linux / Android 上分别构建安装包，最后发布附带
`SHA256SUMS.txt` 的 GitHub Release。Release 正文取自
`release-notes/vX.Y.Z.md`；没有对应文件时正文为空。签名需要的密钥、证书与
GitHub Secrets 见 [发布与签名](docs/RELEASING.md)。

## 许可

[GPL-3.0-only](LICENSE)。第三方依赖遵循各自的许可；分发时请一并保留其许可声明。
启动器的实现思路参考了 PCL / PCL.Core（Apache-2.0）与 HMCL / HMCLCore
（GPL-3.0），本项目为独立实现，并保留上述项目的开源许可。

## 反馈

问题与建议请提交到 [Issues](https://github.com/crystelf/fograin-launcher/issues)，附上启动器日志或诊断包会更快定位。
