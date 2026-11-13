# 雾雨启动器 · Fograin Launcher

Minecraft Java 版启动器：一套 Rust 核心 + 一套 Compose Multiplatform UI（Android / Windows / macOS / Linux）。桌面外壳按平台适配，页面与交互只有一份实现。

设计文档见 [docs/design.md](docs/design.md)，架构决策见 [docs/adr/](docs/adr/README.md)，待办见 [docs/backlog.md](docs/backlog.md)。

## 当前进度

| 里程碑 | 内容 | 状态 |
| --- | --- | --- |
| M0 | 工程骨架、FFI 健康检查、CI | 完成 |
| M1 | 四端原生外壳（导航、主题、窗口行为） | 完成（历史） |
| M2 | 网络与下载引擎（`fg-net`、`fg-task`） | 完成 |
| M3 | 原版安装与启动（`fg-game`、`fg-java`、`fg-auth`、`fg-cli`） | 完成（核心与 CLI） |
| M4 | 实例管理、安装/启动 UI 接线、任务队列、外部导入 | 完成 |
| R0 | Compose Multiplatform 重构：技术验证 | 完成（2026-11-12） |
| R6 | 清理桌面原生客户端与旧文档 | 桌面部分完成（2026-11-13） |

M0–M4 早期实现了四套原生 UI（SwiftUI / WinUI 3 / GTK4 / Compose）。四端 UI 合计约 3 万行，同一个页面要写四遍，维护成本过高。2026-11-12 起按 [ADR 0004](docs/adr/0004-compose-multiplatform-ui.md) 收敛为**一套 Compose Multiplatform UI**：Rust 核心、FFI 契约、数据目录与配置不变；Android 端保留原有 Compose 页面，桌面端用同一份 `commonMain` 运行并各自打包。

R0 已跑通：新工程三模块、Android/desktop 共用 UI、JNI FFI、DMG（71 MB）、冷启动 1.61 s、空闲 RSS 196 MB。三个桌面原生客户端已删除；旧 `apps/android` 保留到 UI 迁移完成。

## 目录

```text
crates/fg-core     模型、配置、错误、数据目录
crates/fg-net      HTTP 客户端、镜像规则、下载引擎、缓存、源健康度
crates/fg-task     任务图、进度聚合、取消/暂停、持久化
crates/fg-game     版本清单、inheritsFrom 合并、rules、安装任务图、启动命令与进程
crates/fg-java     Java 发现、release/PE/Mach-O 解析、版本匹配
crates/fg-auth     离线账户（Yggdrasil / Microsoft 为 M7 / M11 预留）
crates/fg-cli      无 UI 的调试与 CI 入口
bindings/fg-ffi    C ABI / JSON 契约
bindings/fg-ffi-jni JVM JNI 桥（Android .so + 桌面 .dylib/.dll/.so）
apps/compose/      Compose Multiplatform 工程（shared + android + desktop）
apps/android/      旧 Android 客户端（UI 迁移完成后删除）
design/            设计令牌与参考图
docs/              设计文档、ADR、backlog
```

## 构建与测试

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
```

新 UI 工程（R0 起）：

```bash
# 桌面端（当前 OS）
./scripts/build-jni-desktop.sh
./apps/compose/gradlew -p apps/compose :desktop:run

# Android
./scripts/build-android.sh
./apps/compose/gradlew -p apps/compose :android:assembleDebug
```

## 原版安装与启动手测（M3）

```bash
# 查看缓存的版本清单（--refresh 绕过缓存）
cargo run -p fg-cli -- versions --limit 10

# 发现本机 Java；没有 release 文件的 JDK 会跑一次 -XshowSettings
cargo run -p fg-cli -- java list

# 安装原版并创建同名实例；--dry-run 只打印计划
cargo run -p fg-cli -- install 1.21.4
cargo run -p fg-cli -- install 1.21.4 --id my-instance --dry-run

# 打印启动命令（不联网、不启动游戏）
cargo run -p fg-cli -- launch my-instance --username Steve --dry-run

# 离线账户启动，stdout/stderr 会按 log4j 事件格式化后流式输出
cargo run -p fg-cli -- launch my-instance --username Steve

# 查看实例与已选 Java
cargo run -p fg-cli -- instance list
cargo run -p fg-cli -- java match 1.21.4
```

公共选项：`--root $FOGRAIN_HOME`（数据目录）、`--source auto|official|mirror`、`--region cn|global`、`--rate-limit-kib`。

数据目录沿用设计 §4.1：共享的 `shared/versions`、`shared/libraries`、`shared/assets`，实例在 `instances/<id>/`；下载缓存在 `cache/`。实例的 `instance.toml` 已按 `[loader]` / `[override]` / `[meta]` 预留，M4 的下载/实例设置流程不需要迁移。

Java 版本按 `version_json.javaVersion.majorVersion` 选择；缺失时按 §5.5 推断。1.8.9 / 1.12.2 / 1.16.5 需要 Java 8，本机没有时用 `--java <home>` 指定；M6 会补自动下载。Apple Silicon 上这些老版本只有 x86_64 natives，需要用 x86_64 Java 8（Rosetta）；启动前会检查并给出提示。

## 下载引擎手测（M2）

```bash
# 查看 URL 的候选源顺序（镜像规则）
cargo run -p fg-cli -- mirror https://piston-meta.mojang.com/mc/game/version_manifest_v2.json --region cn

# 下载单个文件（可带 sha1/sha256/size 校验）
cargo run -p fg-cli -- download <url> --dest /tmp/file.bin --sha1 <hex>

# 下载某个版本的全部资源对象（M2 验收）
cargo run -p fg-cli -- assets 1.21.4 --dest /tmp/assets-1.21.4 --concurrency 32

# 测速并记录源健康度
cargo run -p fg-cli -- probe https://bmclapi2.bangbang93.com https://piston-meta.mojang.com
```

下载缓存位于 `<root>/cache/`：`sha1/` 内容寻址缓存、`http/` 条件请求缓存、`source-health.json` 源健康度、`mirrors.toml` 可选的镜像表热更新；安装计划在 `cache/install/`，中断后重跑同一命令即可续装。

## 实例管理（M4）

`fg-ffi` 暴露一个异步服务（`fg_service_current` + `fg_rpc_call` + `fg_events_next`），所有命令以 JSON 方法名分派（契约见 `bindings/README.md`）：

```bash
# CLI 与 UI 共用的实例管理面
cargo run -p fg-cli -- instance create my-instance 1.21.4 --name 雾林
cargo run -p fg-cli -- instance list
cargo run -p fg-cli -- instance delete my-instance

# 外部 .minecraft 目录导入（只登记，不迁移文件）
cargo run -p fg-cli -- instance import ~/path/to/.minecraft --name 外部存档
```

UI 侧：主页/实例库读 `instances.list` / `instances.summary`；「添加实例」进入下载页，先选游戏 / 整合包 / 模组，再在游戏页选版本与加载器（原版可用，Fabric/Forge/NeoForge 留待 M5），下载完成自动打开该实例的「游戏设置」，内存/JVM/分辨率等全部在设置页调整；下载队列、底栏角标消费 `fg_events_next` 里的 `task.progress/task.finished`；实例设置页通过 `instances.settings.set` 写 `[override]`，留空即继承 `launcher.toml [game]`；开始游戏走 `launch.start`，会话事件（`game.line` / `game.exited`）在退出后自动累加 `play_seconds` 并刷新「上次游玩」。Android 端通过 `bindings/fg-ffi-jni` 加载同一个 `fg-ffi` 服务，数据根目录由 Kotlin 在首次 RPC 前通过 `fg_set_data_root` 设为应用私有外部目录；当前没有内置 Java 运行时，`launch.start` 会如实返回 `error.java.none_installed`，M6 接入 JRE 后即可启动。

GPL-3.0-only。参考实现与致谢见 `docs/design.md` §11。
