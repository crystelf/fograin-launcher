# M4 交付检查（macOS + core）

- 日期：2026-11-09
- 基线：`d0673fb`（工作树未提交）
- 范围：Rust core + `fg-ffi` + macOS 客户端；Windows / Linux / Android 接线不在本次检查内。

## 结论

**有条件通过（macOS + core）。** M4 的实例管理、下载/安装、设置继承、任务队列、启动会话、外部 `.minecraft` 导入已接通；原版 26.3 已真实安装、真实启动到窗口就绪，退出后 `play_seconds` 正确累加。

严格按设计稿验收口径「完全通过 UI 完成新建 → 安装 → 启动 → 退出 → 游玩时长更新」，还差最后一项证据：在 macOS UI 中实际点击标题栏启动/停止按钮，并观察蓝环、停止提示和时长刷新。代码路径已接，CLI/核心链路已实测；因此当前判定为**有条件通过**，不把 UI 点击验收写成已完成。

M5 加载器安装器、M6 Java 自动下载仍是后续里程碑；当前 Java 选择不强制精确版本，优先使用环境中已发现、可运行的运行时。

## 验收矩阵

| 检查项 | 证据 | 结果 |
| --- | --- | --- |
| 实例 CRUD / `instance.toml` | `crates/fg-game` / `bindings/fg-ffi` 测试；`fg-cli instance list` | 通过 |
| 全局/实例设置继承 | `instances.settings.set` + 空值回退 `launcher.toml [game]`；Swift 设置页写 `[override]` | 通过 |
| 下载页 → 安装对话框 | UI：添加实例进入下载页，选游戏/版本，安装对话框选加载器与实例名；原版可安装，加载器为 M5 预留 | 通过（原版） |
| 任务队列 / 侧栏角标 / Dock 进度 | `task.progress` / `task.finished` 事件；主页队列与 `/下载` 页消费 | 通过 |
| 资源补全与安装完成标记 | 共享安装要求 `shared/versions/<id>/fograin-install.json`；下载失败实例显示未安装 | 通过 |
| 外部 `.minecraft` 导入 | `instances.import` RPC + `fg-cli instance import`；只登记不移动文件 | 通过 |
| 启动 / 会话事件 | 真实启动 26.3，Java 25；窗口就绪后手动终止，`play_seconds = 44` | 通过 |
| 停止提示 | 标题栏停止按钮走 `session.stop`；`game.exited` 且 `stopRequested` 为真时提示「游戏已停止」 | 代码路径已接，UI 点击待复验 |
| Java 选择 | 精确 major 优先；无精确时用最近更高/更低 major，仅无 Java 时报 `none_installed`；`fg-java` 18 项测试 | 通过 |
| Liquid Glass 运行控件 | 系统共享玻璃背景（`sharedBackgroundVisibility(.visible)`）+ 原生 `Button`；实例标签无胶囊、蓝环用原生 `Gauge` | 通过（原生控件） |

## 自动化检查

```text
cargo fmt --all -- --check                  clean
cargo clippy --workspace --all-targets      0 warnings / 0 errors
cargo test --workspace                      213 passed / 0 failed
./scripts/build-macos.sh                    success
./scripts/build-macos.sh --test             23 passed / 0 failed
otool -l apps/macos/.build/debug/Fograin     minos 14.0, sdk 27.0
```

真实启动记录：

```text
started   pid 42920
[fograin] game window is ready
exited    code 143 after 44s
play_seconds = 44
```

## 已知限制 / 交接项

- **M5**：Forge / NeoForge / OptiFine / Fabric / Quilt / Fabric API / SQL API 安装器尚未实现；安装对话框保留选择项，只有原版可安装。
- **M6**：Java 自动下载未实现；当前发现环境 Java 后直接尝试，失败时再引导安装。
- **M4 其余客户端**：Windows / Linux / Android 尚未复用 FFI 接线。
- Rust 静态库在本机以 macOS 27 SDK 构建，链接到 14.0 目标时 `ld` 会打印 “built for newer macOS version” 警告；`LC_BUILD_VERSION` 仍为 `minos 14.0 / sdk 27.0`，不影响运行。
- 停止提示的 UI 自动点击未纳入 CI；停止 RPC、退出事件和游玩时长累加已在真实启动中验证。

## 未提交

本次检查没有创建 commit，保持工作树待审阅状态。
