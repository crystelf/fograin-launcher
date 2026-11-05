# 雾雨启动器 · Fograin Launcher

Minecraft Java 版启动器：一套 Rust 核心 + 四个原生 UI 客户端（macOS SwiftUI、Windows WinUI 3、Linux GTK4、Android Compose）。

设计文档见 [docs/design.md](docs/design.md)，架构决策见 [docs/adr/](docs/adr/README.md)。

## 当前进度

| 里程碑 | 内容 | 状态 |
| --- | --- | --- |
| M0 | 工程骨架、四端工作区、FFI 健康检查、CI | 完成 |
| M1 | 四端原生外壳（导航、主题、原生窗口行为） | 完成 |
| M2 | 网络与下载引擎（`fg-net`、`fg-task`） | 完成 |
| M3 | 原版安装与启动 | 下一步 |

## 目录

```text
crates/fg-core     模型、配置、错误、数据目录
crates/fg-net      HTTP 客户端、镜像规则、下载引擎、缓存、源健康度
crates/fg-task     任务图、进度聚合、取消/暂停、持久化
crates/fg-cli      无 UI 的调试与 CI 入口
crates/fg-*        安装、Java、账户、内容等（后续里程碑）
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

公共选项：`--root $FOGRAIN_HOME`（数据目录）、`--source auto|official|mirror`、`--region cn|global`、`--rate-limit-kib`。

下载缓存位于 `<root>/cache/`：`sha1/` 内容寻址缓存、`http/` 条件请求缓存、`source-health.json` 源健康度、`mirrors.toml` 可选的镜像表热更新。

## 许可

GPL-3.0-only。参考实现与致谢见 `docs/design.md` §1。
