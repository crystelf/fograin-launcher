# 待办与优化

按优先级排列；`P0` 是重构内必须完成，`P1` 是重构后第一个版本，`P2` 是后续。

## 桌面布局

- ✅ **P1 — 桌面自适应布局**：≥840 dp 用 `NavigationRail`，<840 dp 保持底栏；同一套目的地与页面。验收：1280×860 与 960×640 两档离屏截图（`AdaptiveLayoutRenderTest`）。注：两档都 ≥840 dp，因此都走 rail；720×900 作为底栏对照。

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
- P2 — 模型内展示元数据（`LoaderKind.title`、`Instance.meta`）随 M8/M10 改为核心下发后资源化。
- P1 — jlink 裁剪运行时模块；AppCDS；ZGC 参数调优。
- P1 — 桌面图标（`.icns` / `.ico` / PNG）从 `design/app-icon.png` 生成。
- P2 — 自动更新（签名清单 + 增量下载）。
- P2 — 安装包体积压缩与 macOS universal（arm64 + x86_64）构建。

## 加载器（M5 后续）

- P1 — OptiFine 与 Forge 同实例共存：把 OptiFine 作为 `mods/` 补丁而不是独立子版本；需要 Forge 启动链的兼容性验证。
- P1 — 加载器安装前的 Java 版本校验与安装指引：M6 接入运行时下载后，直接下载缺失 major。
- P2 — Fabric API 从“加载器”迁到 M8 的模组内容，支持一键安装前置。
- P2 — 旧版 Forge（1.5 及更早）installer 兼容；当前支持 1.7–1.12 的 `versionInfo` / `version.json`。
- P2 — 加载器版本列表本地缓存与手动刷新；当前每次打开安装页读取元数据（HTTP 条件缓存仍在 `fg-net`）。

## 体验与无障碍

- P1 — 桌面快捷键与菜单（新建实例、搜索、导入、切换主题）。
- P1 — 中文输入法在三 OS 的回归测试；Linux 缺字回退字体。
- P2 — 键盘导航与焦点可见性；屏幕阅读器标签。
- P2 — Windows/Linux 的 HiDPI 与分数缩放验证。
- P2 — 桌面端补齐「关于」页与开源许可列表。

## 工程

- ✅ P1 — CI 增加 Windows/Linux 的 Compose Desktop job（Linux 用 Xvfb）。
- P1 — FFI 契约测试同时覆盖 C ABI 与 JNI（JNI 侧已有 `CoreClientSmokeTest`，C ABI 侧仍只有 `bindings/fg-ffi/tests`）。
- P1 — AppModel 状态机测试已补（`AppModelTest`），仍缺 launch/download 失败分支的联合覆盖。
- ✅ P2 — Compose 截图测试（四条导航 + 三个 sheet + 两档窗口尺寸）。
- ✅ P0 — R1/R2 将模型、`AppModel`、四页与 3 个 sheet 迁入 `apps/compose/shared`；单测迁 `commonTest`。
- ✅ P1 — R3 切换 Android 薄壳（manifest/res/jniLibs/Activity），CI 已构建。
- ✅ P2 — R6 删除旧 `apps/android` 与 `.gitignore`/`.idea` 残留。
- ✅ P1 — `AppModel` 拆分（Library/Downloads/Launch/InstanceSettings 四个 controller）。
- ✅ P1 — 桌面窗口状态、菜单快捷键、Esc 返回、原生目录选择器（R4）。
- P1 — Android instrumented 测试（`connectedDebugAndroidTest`）尚未接入 CI。
