# 雾雨启动器 · Fograin Launcher

Minecraft Java 版启动器：一套 Rust 核心 + 四个原生 UI 客户端（macOS SwiftUI、Windows WinUI 3、Linux GTK4、Android Compose）。

设计文档见 [docs/design.md](docs/design.md)，架构决策见 [docs/adr/](docs/adr/README.md)。

## 当前进度

| 里程碑 | 内容 | 状态 |
| --- | --- | --- |
| M0 | 工程骨架、四端工作区、FFI 健康检查、CI | 完成 |
| M1 | 四端原生外壳（导航、主题、原生窗口行为） | 完成 |
| M2 | 网络与下载引擎（`fg-net`、`fg-task`） | 完成 |
| M3 | 原版安装与启动（`fg-game`、`fg-java`、`fg-auth`、`fg-cli`） | 完成（核心与 CLI；UI 接线在 M4） |
| M4 | 实例管理与界面接线 | 候选完成（macOS + core，2026-11-09；实例 CRUD、HMCL/PCL 式「添加实例 → 下载页 → 安装对话框（加载器/版本/名称）→ 游戏设置」流程、设置继承、任务队列与游戏会话已接线；自动化测试与真实 CLI 启动通过，UI 启动/停止点击待最终复验；加载器 M5、Java 自动下载 M6；Windows/Linux/Android 复用 FFI 契约后接线） |

## 目录

```text
crates/fg-core     模型、配置、错误、数据目录
crates/fg-net      HTTP 客户端、镜像规则、下载引擎、缓存、源健康度
crates/fg-task     任务图、进度聚合、取消/暂停、持久化
crates/fg-game     版本清单、inheritsFrom 合并、rules、安装任务图、启动命令与进程
crates/fg-java     Java 发现、release/PE/Mach-O 解析、版本匹配（精确优先，可用版本回退）
crates/fg-auth     离线账户（Yggdrasil / Microsoft 为 M7 / M11 预留）
crates/fg-cli      无 UI 的调试与 CI 入口
bindings/fg-ffi    C ABI / UniFFI 边界
apps/              四个原生客户端
design/            设计令牌与参考图
docs/              设计文档与 ADR
```

## 构建与测试

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
```

四个客户端有各自的构建方式：`scripts/build-macos.sh`、`dotnet build apps/windows`、`cargo build --manifest-path apps/linux/Cargo.toml`、`./apps/android/gradlew assembleDebug`。

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

数据目录沿用设计 §5.1：共享的 `shared/versions`、`shared/libraries`、`shared/assets`，实例在 `instances/<id>/`；下载缓存在 `cache/`。实例的 `instance.toml` 已按 `[loader]` / `[override]` / `[meta]` 预留，M4 的下载/实例设置流程不需要迁移。

Java 版本按 `version_json.javaVersion.majorVersion` 选择；缺失时按 §8.2 推断。1.8.9 / 1.12.2 / 1.16.5 需要 Java 8，本机没有时用 `--java <home>` 指定；M6 会补自动下载。Apple Silicon 上这些老版本只有 x86_64 natives，需要用 x86_64 Java 8（Rosetta）；启动前会检查并给出提示。

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

UI 侧（macOS 已接线）：主页/实例库读 `instances.summary`；「添加实例」进入下载页，先选游戏 / 整合包 / 模组，再在游戏页选版本与加载器（原版可用，Fabric/Forge/NeoForge 留待 M5），下载完成自动打开该实例的「游戏设置」，内存/JVM/分辨率等全部在设置页调整；下载队列、侧栏角标与 Dock 角标消费 `fg_events_next` 里的 `task.progress/task.finished`；实例设置页通过 `instances.settings.set` 写 `[override]`，留空即继承 `launcher.toml [game]`；开始游戏走 `launch.start`，会话事件（`game.line` / `game.exited`）在退出后自动累加 `play_seconds` 并刷新「上次游玩」。



GPL-3.0-only。参考实现与致谢见 `docs/design.md` §1。
