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
| M4 | 实例管理与界面接线 | 下一步 |

## 目录

```text
crates/fg-core     模型、配置、错误、数据目录
crates/fg-net      HTTP 客户端、镜像规则、下载引擎、缓存、源健康度
crates/fg-task     任务图、进度聚合、取消/暂停、持久化
crates/fg-game     版本清单、inheritsFrom 合并、rules、安装任务图、启动命令与进程
crates/fg-java     Java 发现、release/PE/Mach-O 解析、版本匹配
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

数据目录沿用设计 §5.1：共享的 `shared/versions`、`shared/libraries`、`shared/assets`，实例在 `instances/<id>/`；下载缓存在 `cache/`。实例的 `instance.toml` 已按 `[loader]` / `[override]` / `[meta]` 预留，M4 扩展向导时不需要迁移。

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

## 许可

GPL-3.0-only。参考实现与致谢见 `docs/design.md` §1。
