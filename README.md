# 雾雨启动器 · Fograin Launcher

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

macOS 首次打开需要在
「系统设置 → 隐私与安全性」里允许，或执行
`xattr -dr com.apple.quarantine /Applications/Fograin.app`；

Windows SmartScreen 需要选择「仍要运行」。


## 已知限制

- 暂不支持微软正版登陆，待应用审核通过后将支持。
- 暂不提供皮肤与披风管理；第三方皮肤由服务端与 authlib-injector 在游戏内处理。
- 目标平台为 Windows / macOS / Linux 桌面与 Android；不提供 iOS 与 Web 版本。

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

## 许可

[GPL-3.0-only](LICENSE)。第三方依赖遵循各自的许可；分发时请一并保留其许可声明。
启动器的实现思路部分参考 PCL / PCL.Core（Apache-2.0）与 HMCL / HMCLCore
（GPL-3.0），本项目为独立实现，并保留上述项目的开源许可。

## 反馈

问题与建议请提交到 [Issues](https://github.com/crystelf/fograin-launcher/issues)，附上启动器日志或诊断包可以帮助定位。
