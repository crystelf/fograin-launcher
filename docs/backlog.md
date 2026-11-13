# 待办与优化

按优先级排列；`P0` 是重构内必须完成，`P1` 是重构后第一个版本，`P2` 是后续。

## 桌面布局

- **P1 — 桌面自适应布局**：目前桌面端沿用 Android 的 4 项底栏。窗口 ≥ 840dp 时切换为 `NavigationRail`（或侧栏），< 840dp 保持底栏；使用 `androidx.compose.material3.adaptive` 的 `NavigationSuiteScaffold`。要求：同一套目的地与页面，不改内容区布局；记住用户最后一次窗口形态。验收：1280×860 与 960×640 两档截图。

## 桌面外壳（v2）

- P2 — macOS：Dock 徽标与任务进度（`NSDockTile`）。
- P2 — Windows：Mica/Acrylic 背景、自定义标题栏、任务栏进度（`ITaskbarList3`）。
- P2 — Linux：Unity LauncherEntry / DBus 进度（可用时）。
- P2 — 托盘与「关闭到托盘」；单实例锁（Windows mutex / Unix socket）。
- P2 — 系统通知（`UNUserNotificationCenter` / AppNotification / GNotification）。
- P2 — 文件拖放导入（`.mrpack`、`.zip`、`.minecraft` 目录）。

## 性能与打包

- ✅ P0 — 记录 R0 基线：安装包体积、安装后体积、冷启动、空闲 RSS。
- P1 — 中文输入法在三 OS 的回归测试（R1/R2 页面已迁入 `commonMain`，待人工验收）。
- P2 — `AppModel` 的运行期提示/错误文案仍内嵌中文；随 M10 i18n 一起迁入 `composeResources`。
- P2 — 模型内展示元数据（`LoaderKind.title`、`Instance.meta`、加载器占位版本）随 M5/M8 改为核心下发后资源化。
- P1 — jlink 裁剪运行时模块；AppCDS；ZGC 参数调优。
- P1 — 桌面图标（`.icns` / `.ico` / PNG）从 `design/app-icon.png` 生成。
- P2 — 自动更新（签名清单 + 增量下载）。
- P2 — 安装包体积压缩与 macOS universal（arm64 + x86_64）构建。

## 体验与无障碍

- P1 — 桌面快捷键与菜单（新建实例、搜索、导入、切换主题）。
- P1 — 中文输入法在三 OS 的回归测试；Linux 缺字回退字体。
- P2 — 键盘导航与焦点可见性；屏幕阅读器标签。
- P2 — Windows/Linux 的 HiDPI 与分数缩放验证。
- P2 — 桌面端补齐「关于」页与开源许可列表。

## 工程

- P1 — CI 增加 Windows/Linux 的 Compose Desktop job（macOS 与 Android 已接入）。
- P1 — FFI 契约测试同时覆盖 C ABI 与 JNI。
- P2 — Compose 截图测试（四条导航 + 三个 sheet）。
- ✅ P0 — R1/R2 将模型、`AppModel`、四页与 3 个 sheet 迁入 `apps/compose/shared`；单测迁 `commonTest`。
- P1 — R3 切换 Android 薄壳（manifest/res/jniLibs/Activity）并让 CI 双跑。
- P2 — R6 删除旧 `apps/android` 与对应的生成脚本残留。
