# M4 交付检查（core + macOS + Android）

- 日期：2026-11-09
- 基线：`e879dc8`（M4 core + macOS 基线）
- 范围：Rust core + `fg-ffi` + macOS 客户端 + Android 客户端；Windows / Linux 接线不在本次检查内。

## 结论

**有条件通过（core + macOS + Android）。** M4 的实例管理、下载/安装、设置继承、任务队列、启动会话、外部 `.minecraft` 导入已接通。macOS 原版 26.3 已真实安装、真实启动到窗口就绪；Android 通过 JNI 复用同一 JSON 契约，在 `fograin-m1` 模拟器完成 26.3 的真实安装（5216 文件 / 610.6 MB），下载完成自动进入「游戏设置」，内存覆盖写回 `[override]`，主题写回 `launcher.toml`。

严格按设计稿验收口径「完全通过 UI 完成新建 → 安装 → 启动 → 退出 → 游玩时长更新」：

- **macOS** 还差最后一项证据：在 UI 中实际点击标题栏启动/停止按钮，并观察蓝环、停止提示和时长刷新。代码路径已接，CLI/核心链路已实测。
- **Android** 的启动路径已接线并验证了错误分支（无 Java 运行时 → `error.java.none_installed`）；真正启动需要 M6 的内置/自动下载 JRE，当前不能完成「启动 → 退出 → 时长更新」。因此 Android 判定为**接线完成、启动验收待 M6**，不把未发生的启动写成已完成。

## 验收矩阵

| 检查项 | 证据 | 结果 |
| --- | --- | --- |
| 实例 CRUD / `instance.toml` | `crates/fg-game` / `bindings/fg-ffi` 测试；`fg-cli instance list` | 通过 |
| 全局/实例设置继承 | `instances.settings.set` + 空值回退 `launcher.toml [game]`；macOS 设置页写 `[override]`；Android 设置页写 `memory_mb = 4096` | 通过 |
| 下载页 → 安装对话框 | UI：添加实例进入下载页，选游戏/版本，安装对话框选加载器与实例名；原版可安装，加载器为 M5 预留 | 通过（原版） |
| 任务队列 / 角标 | `task.progress` / `task.finished` 事件；macOS 主页队列与 Dock 角标，Android 主页/下载页队列与底栏角标 | 通过 |
| 资源补全与安装完成标记 | 共享安装要求 `shared/versions/<id>/fograin-install.json`；下载失败实例显示未安装 | 通过 |
| 外部 `.minecraft` 导入 | `instances.import` RPC + `fg-cli instance import`；只登记不移动文件 | 通过 |
| macOS 启动 / 会话事件 | 真实启动 26.3，Java 25；窗口就绪后手动终止，`play_seconds = 44` | 通过 |
| macOS 停止提示 | 标题栏停止按钮走 `session.stop`；`game.exited` 且 `stopRequested` 为真时提示「游戏已停止」 | 代码路径已接，UI 点击待复验 |
| Java 选择 | 精确 major 优先；无精确时用最近更高/更低 major，仅无 Java 时报 `none_installed`；`fg-java` 18 项测试 | 通过 |
| Liquid Glass 运行控件 | 系统共享玻璃背景 + 原生 `Button`；实例标签无胶囊、蓝环用原生 `Gauge` | 通过（原生控件） |
| Android JNI 边界 | `bindings/fg-ffi-jni` 导出 7 个 `Java_com_fograin_launcher_platform_CoreNative_*` 符号；`llvm-nm` 验证 | 通过 |
| Android 数据根目录 | `fg_set_data_root` 在首次 RPC 前设为 `/sdcard/Android/data/com.fograin.launcher/files`；`launcher.toml`、`instances/`、`shared/` 均落在该目录 | 通过 |
| Android 实例/设置页 | `instances.list` 驱动实例库；设置页读 `instances.settings.get`，内存 4 GB 写回 `[override]` | 通过 |
| Android 下载/安装 | `versions.list` → `version.plan` → `install.start`；26.3 安装完成并自动打开「游戏设置」 | 通过 |
| Android 事件/角标 | `task.progress` 驱动进度条与底栏角标；`task.finished` 触发安装完成流程 | 通过 |
| Android 主题 | 设置页切换深色 → `launcher.toml` 写入 `theme = "dark"`，界面即时切换 | 通过 |
| Android 启动 | `launch.start` 已接线；无 JRE 时如实弹出「未发现 Java 运行时」，待 M6 | 接线通过，启动待 M6 |
| Android 原生控件 | Material 3 `NavigationBar`、`LargeTopAppBar`、`FilterChip`、`SegmentedButton`、`ModalBottomSheet`、`AlertDialog`、`Snackbar`、`DropdownField` 等；无自绘假控件 | 通过（原生控件） |

## 自动化检查

```text
cargo fmt --all -- --check                  clean
cargo clippy --workspace --all-targets      0 warnings / 0 errors
cargo test --workspace                      214 passed / 0 failed
./scripts/build-macos.sh --test             23 passed / 0 failed
./scripts/build-android.sh --abi arm64-v8a  success (16 KB page aligned)
./apps/android/gradlew assembleDebug         success
./apps/android/gradlew testDebugUnitTest     23 passed / 0 failed
```

真实安装记录（Android / `fograin-m1`，API 35，arm64）：

```text
versions.list  26.3 → Java 25 · 5216 文件 · 610.6 MB
install.start  task 队列 6.2 MB/s → 99% → 完成
shared/        613 MB
instances/26.3/instance.toml  [override] memory_mb = 4096
shared/versions/26.3/fograin-install.json  已写入
UI             task.finished 后自动进入实例库并打开「游戏设置」
launch.start   error.java.none_installed（当前无 JRE，M6 接入）
```

## 已知限制 / 交接项

- **M5**：Forge / NeoForge / OptiFine / Fabric / Quilt / Fabric API / SQL API 安装器尚未实现；安装对话框保留选择项，只有原版可安装。
- **M6**：Java 自动下载未实现。macOS 使用环境 Java；Android 没有可用 JRE，`launch.start` 会返回 `error.java.none_installed`，因此 Android 的真实启动与 `play_seconds` 累加要等 M6 的内置 JRE。
- **M4 其余客户端**：Windows / Linux 由各自的交付文档覆盖；本文件只记录 core + macOS + Android。
- **Android 导入**：Scoped storage 不能把 SAF tree 直接交给 Rust 核心，当前用应用内目录选择器浏览数据根目录（USB 可访问）；跨应用 `.minecraft` 目录的导入需要后续的复制/虚拟文件系统方案。
- **Android 目录操作**：没有访达等价物，实例目录操作改为「复制实例目录路径」并写入剪贴板；设置页不再展示数据目录。
- **Android `:game` 进程**：GameActivity/独立进程、渲染器选择仍按 §11 留待后续里程碑；当前启动路径在应用进程内调用核心。
- Rust 静态库在本机以 macOS 27 SDK 构建，链接到 14.0 目标时 `ld` 会打印 “built for newer macOS version” 警告；`LC_BUILD_VERSION` 仍为 `minos 14.0 / sdk 27.0`，不影响运行。
- Android 的实例 `sizeBytes` 来自实例目录，原版实例的共享资源不计入，因此新实例显示「占用 0 KB」；这是核心口径，macOS 表现一致，待后续统一。

## 验收前 UI 规范检查（2026-11-10）

- 实例目录相关入口统一改为「复制实例目录路径」并写入剪贴板，不再尝试打开文件管理器。
- 下载页顶栏重构：版本通道/加载器下拉位于搜索图标左侧，搜索图标与实例库一致，右侧三点菜单预留「下载设置」（当前以提示占位，不进入设置页）。
- 设置页删除主题下方的「当前：…」说明，并删除整个「数据目录」分组。
- 主页 Hero 的「开始游戏」「游戏设置」改为右对齐。

## 提交

已提交：`feat(M4): 交付 Android 原生界面接线`。
