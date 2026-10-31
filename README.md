# 雾雨启动器 · Fograin Launcher

Minecraft Java 版启动器。设计文档见 [docs/design.md](docs/design.md)，当前进度：**M0 工程骨架**。

## 技术栈

- 外壳：Tauri 2
- 后端：Rust 2024 edition，核心逻辑在 `crates/fg-*`，不依赖 Tauri
- 前端：React 19 + TypeScript + Vite，Tailwind CSS v4
- IPC 类型：tauri-specta 生成 `ui/src/ipc/bindings.ts`
- 状态 / 路由 / 数据：Zustand、TanStack Router、TanStack Query

## 开发

前置：Rust 1.90+、Node.js 22.12+、pnpm 10，以及 [Tauri 2 各平台系统依赖](https://v2.tauri.app/start/prerequisites/)。

```bash
pnpm install          # 安装前端依赖
pnpm bindings         # 由 Rust 重新生成 ui/src/ipc/bindings.ts
pnpm tauri dev        # 启动桌面窗口（Vite 热更新）
```

常用检查：

```bash
pnpm typecheck        # TypeScript 类型检查
pnpm build            # 构建前端资源
cargo test --workspace
cargo fmt-check       # 别名见 .cargo/config.toml
cargo lint            # clippy --workspace --all-targets -D warnings
pnpm tauri build      # 生成未签名安装包（M0 用于验证三平台可打包）
```

日志写到数据目录下的 `logs/launcher-YYYY-MM-DD.log`；panic 会额外写入
`logs/crash-YYYYMMDD-HHMMSS.log`。开发时可用 `FOGRAIN_HOME=/tmp/fograin` 改变数据根目录。

`src-tauri/icons/` 里的应用图标是 M0 占位图（「雾」+ 三道雾线），品牌稿确定后替换。

## 目录

```text
crates/      fg-core 与各里程碑占位 crate（fg-net、fg-game、…）
src-tauri/   Tauri 命令、窗口、日志与平台适配
ui/          React 前端、平台皮肤令牌与 tauri-specta 绑定
docs/        设计文档
```

## 许可

GPL-3.0-only，见 [LICENSE](LICENSE)。
