# 0004 — 用一套 Compose Multiplatform UI 取代四个原生客户端

- 状态：已接受（2026-11-12）
- 取代：早期的四端原生 UI 决策（记录已随重构删除）
- 相关：`docs/design.md` §2、§3、§7

## 背景

M0–M4 早期实现了四套原生 UI：macOS SwiftUI/AppKit、Windows C#/WinUI 3、Linux Rust/GTK4、Android Kotlin/Compose。

到 M4 结束时：

- 四端 UI 合计约 3 万行；同一个页面（主页、实例库、下载、设置、安装对话框、实例设置）要写四遍。
- M4 的实例管理、设置继承、任务队列、启动会话在四端各接线一次，回归测试也要做四遍。
- 四个工具链意味着四套 CI、四套发布流程、四套截图验收；Android 上先修好的交互问题无法复用到桌面。
- 设计令牌虽然共享，但控件尺寸、菜单位置、窗口行为仍在持续漂移。

Android 的 Compose 实现已经是四端中完成度最高、迭代最快的一份。把这份 UI 作为唯一实现，桌面端用 Compose Multiplatform 复用，是维护成本最低的路径。

## 决策

1. **保留** Rust 核心（`crates/fg-*`）与现有 FFI JSON 契约；不重写业务逻辑。
2. **UI 收敛为一套 Compose Multiplatform 代码**，放在 `apps/compose/shared`：
   - `commonMain`：全部页面、组件、主题、`AppModel`、模型。
   - `jvmMain`：Android 与桌面共用的 JVM 代码（`java.io`、`java.time`、JNI 加载、JNA）。
   - `androidMain` / `desktopMain`：只放平台实际实现。
3. **模块拆分**：
   - `apps/compose/shared`：KMP library（`androidTarget()` + `jvm("desktop")`）。
   - `apps/compose/android`：`com.android.application` 薄壳（Activity、manifest、res、jniLibs）。
   - `apps/compose/desktop`：JVM 薄壳（`main`、窗口、菜单、jpackage）。
4. **JNI 复用**：`bindings/fg-ffi-jni` 同时构建 Android（`.so`）与桌面（`.dylib` / `.dll` / `.so`）；`CoreNative` 的包名与对象名保持不变，Rust 侧零改动。
5. **桌面打包**：Compose Desktop + jpackage，每个 OS 各自产出 DMG / MSI / DEB；JRE 用 JBR/Temurin 21 经 jlink 裁剪。
6. **平台外壳**：窗口、菜单、文件选择、系统主题、原生库加载走 `expect/actual` 或桌面薄壳；不使用 WebView，不做 CSS 皮肤。
7. **版本基线**：Kotlin 2.4.x、Compose Multiplatform 1.12.x、AGP 9.1.x、Gradle 9.3.x、JDK 21（与 Animeko 的已验证组合对齐）。
8. **迁移方式**：新工程与旧客户端并存，按 `docs/design.md` §7.2 的 R0–R6 逐步切换；R6 时删除旧客户端。

## 结果

正面：

- UI 代码从约 3 万行降到约 7 千行，一份实现覆盖 Android / Windows / macOS / Linux。
- 新功能只写一次，四端同步获得；设计令牌、字符串、图标只有一份。
- Android 与桌面共享 `AppModel`、FFI 客户端和测试；M5 之后的核心工作不再需要"每端各接一次"。
- 桌面端获得 Compose Desktop 的成熟能力：窗口状态、菜单、文件选择、主题检测都有现成库。

代价：

- 桌面端变成 JVM 应用：安装包约 60–100 MB，冷启动约 1–2.5 s，空闲内存约 150–300 MB；需要 R0 实测确认可接受。
- 桌面中文输入法、无障碍、HiDPI、字体回退依赖 Skiko，需要逐项验证。
- macOS 签名/公证、Windows MSI/WiX、Linux 字体与 GL 依赖成为发布流程的一部分。
- 桌面端沿用 Android 的 4 项底栏布局；宽屏自适应（NavigationRail）列入 backlog，不在本次重构内。

## 备选方案

| 方案 | 结论 |
| --- | --- |
| 保留四端原生 UI | 维护成本最高，否决 |
| KMP 只共享业务逻辑，UI 仍原生 | 省不了 UI 维护，还要多维护一层桥，否决 |
| Rust UI（Slint / egui / iced） | 体积小、无 JVM，但 Material 3 视觉与中文输入法要重写，与本项目设计不符 |
| Tauri / WebView | 早期已否决：窗口材质、输入法、无障碍冲突 |

## 备注

- iOS 不在目标内：无法运行 Minecraft Java 的 JVM/子进程。
- Web 不在目标内：浏览器不能启动游戏进程。
- 新增依赖需通过 GPL-3.0 兼容性检查（FileKit、JNA、jSystemThemeDetector 等）。
