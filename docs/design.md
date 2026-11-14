# 雾雨启动器（Fograin Launcher）设计文档

> 状态：v1.0 · 2026-11-12 · 单 UI（Compose Multiplatform）架构
> 范围：Minecraft Java 版启动器。一套 Rust 核心（`fg-*`），一套 Compose Multiplatform UI（Android / Windows / macOS / Linux），平台外壳按平台适配；Android 的游戏运行时（内置 JRE + LWJGL 移植）仍是第二阶段。
> 架构决策：[ADR 0004](adr/0004-compose-multiplatform-ui.md)。早期四端原生 UI 的决策记录已随重构删除，内容保留在 §1。

## 0. 目标与非目标

### 0.1 产品目标

- 安装、管理、启动 Minecraft Java 版的全部版本（含快照、远古版本）。
- 加载器：Fabric、Quilt、Forge、NeoForge、OptiFine（LiteLoader、LegacyFabric、Cleanroom 视需求再加）。
- 实例（版本隔离）管理：模组、资源包、光影、世界、原理图、日志与崩溃报告、导出与备份。
- Modrinth / CurseForge 资源浏览与安装，整合包导入导出。
- Java 运行时自动匹配与下载。
- 账户：离线、LittleSkin、authlib-injector（Yggdrasil 外置登录）。
- 服务器列表（收藏、局域网发现、状态 Ping）。
- 国内网络友好：BMCLAPI 等镜像源、自动回退。
- 以 `fograin.pen` 的布局、信息架构和视觉语言为参考，用**一套 Compose Multiplatform UI** 覆盖四个平台；平台外壳（窗口、菜单、文件选择、标题栏）按平台适配；**不做 WebView + CSS 皮肤**。控件使用 Material 3，页面层级、信息密度和操作路径保持一致。

### 0.2 非目标（第一阶段）

- 微软正版登录：延后到 M11（需 Mojang 审核 Azure 应用）。
- 基岩版、iOS：不提供。iOS 无法运行 Minecraft Java 的 JVM/子进程。
- Web 端：浏览器不能启动游戏进程，WebAssembly 目标不在范围内。
- 内置联机（EasyTier / Terracotta 类方案）：M12 以后。
- 自绘皮肤/主题编辑器：使用设计令牌与系统深色模式即可。

## 1. 现状、重构动机与结论

### 1.1 已完成

| 里程碑 | 内容 | 状态 |
| --- | --- | --- |
| M0 | Rust workspace、四端骨架、FFI 健康检查、CI | 完成 |
| M1 | 四端原生外壳（导航、主题、窗口行为） | 完成 |
| M2 | 下载引擎与任务图（`fg-net`、`fg-task`） | 完成 |
| M3 | 原版安装、Java 发现、离线启动（`fg-game`、`fg-java`、`fg-auth`、`fg-cli`） | 完成 |
| M4 | 实例 CRUD、设置继承、安装/启动 UI 接线、任务队列、外部 `.minecraft` 导入 | 完成（core + 四端） |

### 1.2 为什么重构

M0–M4 早期实现了四套原生 UI：SwiftUI/AppKit、WinUI 3、GTK4/libadwaita、Jetpack Compose。

- 四端 UI 合计约 3 万行，同一个页面要写四遍；M4 的实例管理、安装对话框、任务队列在四端各接一次。
- 四个工具链（Xcode、.NET/WinUI、GTK4+Rust、Android Gradle）意味着四套 CI、四套发布流程、四套截图验收。
- 功能修复与交互调整在四端反复回归；Android 上先修好的问题无法复用到桌面。
- 设计令牌虽然共享，但控件行为、间距、菜单位置仍在漂移。

结论：**保留 Rust 核心与 FFI 契约，UI 收敛为一套 Compose Multiplatform 代码**。这与原目标（页面层级与信息架构一致、不做 WebView）不冲突，只是把"四份原生实现"换成"一份共享 Compose + 平台外壳"。

### 1.3 代价（必须接受）

桌面端从原生应用变成带 JRE 的 JVM 应用：

| 指标 | 原生桌面（现状） | Compose Desktop（预期） |
| --- | --- | --- |
| 安装包 | 5–20 MB | 60–100 MB |
| 安装后体积 | 15–40 MB | 100–160 MB |
| 冷启动 | 0.1–0.5 s | 0.8–2.5 s |
| 空闲内存 | 50–100 MB | 150–300 MB |

以上为估算，P0 会在 macOS / Windows / Linux 上实测并把结果写进 §7.2。若体积或启动超出可接受范围，再评估 jlink/AppCDS 优化或回退方案。

## 2. 总体架构

架构决策见 [ADR 0004](adr/0004-compose-multiplatform-ui.md)，取代早期的四端原生 UI 方案。

```text
┌──────────────────────────────────────────────────────────────┐
│ Compose Multiplatform UI（:shared commonMain）                │
│  主页 / 实例库 / 下载 / 设置 / 实例设置 / 安装对话框            │
│  AppModel（状态、事件泵、安装与启动流程）                       │
└───────────────┬──────────────────────────────┬───────────────┘
                │ expect/actual                 │
     ┌──────────▼──────────┐        ┌──────────▼──────────┐
     │ :android 薄壳        │        │ :desktop 薄壳        │
     │ MainActivity         │        │ main / Window / 菜单 │
     │ edge-to-edge / 动态色 │        │ jpackage / 窗口状态   │
     └──────────┬──────────┘        └──────────┬──────────┘
                │ JNI                           │ JNI
                └──────────────┬────────────────┘
                     libfg_ffi_jni(.so/.dylib/.dll)
                               │
                     ┌─────────▼─────────┐
                     │ fg-ffi（JSON RPC） │
                     │  + 事件队列        │
                     └─────────┬─────────┘
        ┌──────────┬───────────┼───────────┬──────────┐
     fg-core    fg-net      fg-task     fg-game     fg-java
     fg-auth    fg-content  fg-loader   fg-world    fg-diag
```

### 2.1 分层原则

1. `crates/fg-*` 不依赖任何 UI 框架，也不依赖 Android/iOS SDK。
2. UI 只通过 `bindings/fg-ffi` 的 JSON 契约访问核心；不再有"Linux 直接链接 crate"的特例。
3. 共享 UI 放在 `:shared`，平台外壳只保留窗口、生命周期、文件选择、通知等平台能力。
4. 平台差异用 `expect/actual` 表达，不把 `Context`、`java.awt`、`Activity` 等类型泄漏到 `commonMain`。
5. 设计令牌、FFI 契约、测试夹具是跨端唯一共享的"横向"资产。

### 2.2 技术栈

| 层 | 选型 | 说明 |
| --- | --- | --- |
| 核心 | Rust stable 1.90+，tokio | `fg-*` crate：下载、安装、Java、账户、实例、启动 |
| 边界 | `fg-ffi`（C ABI）+ `fg-ffi-jni`（JVM） | 两者共用 JSON 请求/响应；JNI 只做字符串与异常封送 |
| UI | Kotlin 2.4.x + Compose Multiplatform 1.12.x + Material 3 | Android 与桌面共享 `commonMain` |
| 构建 | Gradle 9.3.x + AGP 9.1.x + JDK 21 | 版本与 Animeko 的已验证组合对齐 |
| Android | minSdk 26、compileSdk 37、AGP 内建 Kotlin | 原生库按 ABI 打进 APK |
| 桌面 | Compose Desktop（Skiko）+ jpackage | 每个 OS 上各自打包；JRE 用 JBR/Temurin 21 |
| 桌面平台库 | JNA、FileKit、jSystemThemeDetector | Win32/DWM、文件与目录选择、系统深浅色 |
| HTTP | reqwest + rustls | 关掉 native-tls，避免 Linux OpenSSL 依赖 |
| 序列化 | serde / serde_json / toml（Rust）；kotlinx.serialization（Kotlin） | UI 侧不再使用 Android 的 `org.json` |
| 压缩 / NBT / 哈希 | zip、flate2、fastnbt、sha1/sha2/md-5/murmur2 | 与原设计一致 |
| 日志 | tracing + tracing-appender | UI 通过事件队列消费 `game.line` / 日志摘要 |
| 设计令牌 | `design/tokens.json` → `Tokens.kt` | 单一来源；桌面与 Android 共用 |

### 2.3 模块与目录

```text
fograin-launcher/
├── Cargo.toml                    # Rust workspace
├── crates/                       # fg-core / net / task / game / java / auth / content / loader / world / diag / cli
├── bindings/
│   ├── fg-ffi/                   # C ABI + JSON RPC（CLI、测试、未来宿主）
│   └── fg-ffi-jni/               # Android + 桌面 JVM 的 JNI 薄层
├── apps/
│   ├── compose/                  # 唯一 UI 工程（Gradle root）
│   │   ├── shared/               # KMP：common / jvm / android / desktop
│   │   ├── android/              # Android 薄壳：Activity、manifest、res、jniLibs
│   │   └── desktop/              # JVM 薄壳：main、窗口、菜单、jpackage
├── design/                       # tokens.json / assets / screens
├── docs/                         # 本文件、ADR、backlog
└── .github/workflows/            # Rust + Compose（macOS/Linux/Windows 桌面 + Android）+ release
```

`shared` 的 source set：

```text
commonMain      Compose 页面、AppModel 门面 + 四个 feature controller、模型、FFI 客户端
 └── jvmMain    java.io / java.time / JNI 加载 / JNA（Android 与桌面共用）
      ├── androidMain   Context、Activity、SharedPreferences、动态取色
      └── desktopMain   AWT/Swing 窗口状态、菜单、JFileChooser、系统主题、jpackage 资源
commonTest
 └── jvmTest
      ├── androidHostTest
      └── desktopTest
```

**JNI 符号约束**：Rust 导出 `Java_com_fograin_launcher_platform_CoreNative_*`。`CoreNative` 的包名、对象名、方法名在重构中保持不变，因此 `fg-ffi-jni` 无需改 Rust 代码，只需增加桌面目标。

### 2.4 FFI 契约

`fg-ffi` 暴露一个异步服务：`fg_service_current` + `fg_rpc_call` + `fg_events_next`。所有命令是 `{"method": "...", "params": {...}}`，返回 `{"ok": true, ...}` 或 `{"ok": false, "error": {kind, messageKey, detail}}`。完整方法表见 [bindings/README.md](../bindings/README.md)。

已实现的方法（M4）：

- 应用：`app.snapshot`
- 实例：`instances.list` / `.summary` / `.create` / `.import` / `.rename` / `.favorite` / `.delete` / `.settings.get` / `.settings.set` / `.verify` / `.dir`
- 版本与安装：`versions.list` / `versions.news` / `version.plan` / `install.start`
- 任务：`tasks.list` / `tasks.cancel`
- 启动：`launch.start` / `launch.status` / `session.wait` / `session.stop`
- 日志与设置：`game.log.recent` / `gameConfig.get` / `gameConfig.set`

约定：

- 长任务返回 `taskId`，进度通过 `task.progress` / `task.finished` 事件推送；UI 不阻塞等待网络或安装。
- 事件还包括 `game.started`、`game.windowReady`、`game.line`、`game.exited`。
- 事件队列每 250 ms 拉取一次（Android 桌面一致）。
- 大列表分页；图标/缩略图用缓存文件路径，不把二进制塞进 FFI。
- 破坏性变更必须提升 `fg_abi_version()`，四个平台同时升级。
- 字符串由调用方释放（C ABI 用 `fg_string_free`；JNI 直接返回 `jstring`）。

### 2.5 线程模型

- JNI 调用是阻塞的（RPC 在核心的 Tokio 运行时上派发），Kotlin 侧统一走 `Dispatchers.IO`。
- `AppModel` 的协程作用域：Android 用 `Dispatchers.Main.immediate`；桌面用 `Dispatchers.Main.immediate` + `kotlinx-coroutines-swing`，保证回到 AWT 事件线程。
- 事件泵、下载轮询、启动会话轮询都在 `AppModel` 内，Compose 只订阅 `StateFlow`。
- 首帧前完成 `fg_settings_snapshot()`（主题、数据目录），避免启动闪烁。

## 3. 单 UI 设计

### 3.1 共享范围

`commonMain` 包含：

- 四个目的地：主页、实例库、下载、设置（Android 现有底栏结构）。
- 实例预览 sheet、实例设置 sheet、安装对话框、目录导入对话框、提示与告警。
- 主题（颜色/字体/圆角/间距）、通用组件（状态胶囊、进度条、实例封面、下拉框、账户头像）。
- 模型与格式化：`Instance`、`LibraryQuery`、`DownloadFilters`、`InstanceFormat`（kotlinx-datetime）。
- `AppModel` / `AppState`：实例 CRUD、安装、启动、任务、事件、notice/alert。

不共享：

- 窗口与标题栏、应用菜单、Dock/任务栏、托盘。
- 文件/目录选择器的原生实现。
- 系统深浅色检测、动态取色、状态栏/导航栏。
- 原生库加载路径、数据根目录、剪贴板/通知。

### 3.2 平台适配清单

| 能力 | Android | macOS | Windows | Linux |
| --- | --- | --- | --- | --- |
| 数据根 | `getExternalFilesDir` | `~/Library/Application Support/Fograin` | `%APPDATA%\Fograin` | `$XDG_DATA_HOME/fograin` |
| 主题 | `isSystemInDarkTheme` + Material You | jSystemThemeDetector | jSystemThemeDetector | jSystemThemeDetector / GTK |
| 窗口 | 系统窗口 | `Window` + 透明标题栏 | `Window` + 深色标题栏 | `Window` + 系统装饰 |
| 文件选择 | SAF 目录树 → 映射回路径，失败回退应用内浏览 | `JFileChooser` | `JFileChooser` | `JFileChooser` |
| 剪贴板 | Compose ClipboardManager | 同左 | 同左 | 同左 |
| 返回 | 系统返回 / BackHandler | Esc / BackHandler | Esc / BackHandler | Esc / BackHandler |
| 原生库 | `System.loadLibrary` | 解压 resource 后 `System.load` | 同 macOS（dll） | 同 macOS（so） |
| 动态取色 | 支持（Android 12+） | 关闭 | 关闭 | 关闭 |
| 通知/角标 | 通知渠道 | 可选 Dock 徽标 | 可选任务栏进度 | 可选 DBus |

### 3.3 设计令牌与主题

- `design/tokens.json` 是唯一来源，只生成 `apps/compose/shared/src/commonMain/.../generated/Tokens.kt`。
- 语义色、字号、间距、圆角、窗口尺寸全部来自令牌；代码里不写十六进制颜色。
- `appearance.theme = system | light | dark` 由 Rust 核心写入 `launcher.toml`，UI 首帧前读取。
- Android 保留可选的 Material You 动态色；桌面不提供动态色开关。
- 字体：默认使用系统字体（Android Roboto、macOS PingFang/SF、Windows Microsoft YaHei、Linux Noto Sans CJK）。Linux 包内自带 Noto Sans SC 回退，避免缺字。
- 浅色令牌仍是初稿；等 `fograin.pen` 补浅色画面后统一更新。

### 3.4 桌面外壳（v1，R4 已实现）

- 窗口：默认 1120×780；位置/大小/最大化写入 `java.util.prefs`（`window.*`），下次启动恢复。
- 菜单：文件（新建实例、前往实例库、退出）、编辑（搜索实例、切换主题）、前往（四个目的地）。
- 快捷键：`Cmd/Ctrl+N`、`Cmd/Ctrl+I`、`Cmd/Ctrl+F`、`Cmd/Ctrl+Shift+T`、`Cmd/Ctrl+1..4`、`Cmd/Ctrl+Q`、`Esc`。
- `Esc` 走共享的 `BackDispatcher`：先关 sheet/对话框，再回主页；Android 的返回手势是同一实现。
- 宽度 ≥ 840 dp 用 `NavigationRail`，否则用底栏；两档离屏截图作为验收（`AdaptiveLayoutRenderTest`）。
- macOS 透明标题栏、Windows DWM 深色标题栏、托盘、任务栏进度、单实例锁、通知：v2，见 `docs/backlog.md`。

### 3.5 已知差异与优化项

- 自适应布局已实现（≥840 dp 切 `NavigationRail`）。仍需按窗口形态记住用户偏好、以及 1280 dp 以上的三栏布局，见 [backlog](backlog.md)。
- Android 的 `LargeTopAppBar`、`ModalBottomSheet` 在桌面可用，但手势与滚动回弹按桌面习惯调优属于 v2。
- 桌面端不做像素级复刻；以页面结构、信息密度和操作路径一致为准。

## 4. 数据目录与配置

### 4.1 目录布局

根目录（可在设置中更改）：

- macOS：`~/Library/Application Support/Fograin`
- Windows：`%APPDATA%\Fograin`
- Linux：`$XDG_DATA_HOME/fograin`（默认 `~/.local/share/fograin`）
- Android：应用私有目录；首次 RPC 前 `fg_set_data_root(getExternalFilesDir(null))`
- 便携模式：`FOGRAIN_HOME` 环境变量覆盖

```text
<root>/
├─ launcher.toml              # 全局设置
├─ accounts.json              # 账户元数据（令牌在系统凭据库）
├─ servers.json               # 启动器级服务器收藏
├─ shared/
│  ├─ versions/<id>/
│  ├─ libraries/
│  ├─ assets/{indexes,objects,log_configs,virtual/legacy}
│  └─ natives/<instance-hash>/
├─ instances/<id>/
│  ├─ instance.toml
│  ├─ .minecraft/             # mods / resourcepacks / shaderpacks / saves / logs / crash-reports …
│  └─ icon.png
├─ runtimes/<vendor>-<version>-<arch>/
├─ cache/
│  ├─ sha1/<aa>/<sha1>        # 内容寻址缓存
│  ├─ http/                   # ETag / Last-Modified
│  └─ icons/
├─ backups/<instance-id>/<timestamp>.zip
└─ logs/launcher-YYYY-MM-DD.log
```

说明：

- 共享模式下 `versions`、`libraries`、`assets` 只存一份；关闭共享时每个实例自带完整目录，方便整体拷走。
- 外部 `.minecraft` 只登记、不迁移，作为只读外部仓库。
- 缓存按 SHA-1 去重；BMCLAPI 的 `x-bmclapi-hash` 可用于提前命中。

### 4.2 配置格式

`launcher.toml` 分区：`[general] [game] [java] [download] [appearance] [network] [notify] [advanced]`，与设置页分组对应。

`instance.toml`：

```toml
id = "01JBX..."
name = "雾林生存"
icon = "icon.png"
minecraft = "1.21.4"

[loader]
kind = "fabric"            # vanilla|fabric|quilt|forge|neoforge
version = "0.16.9"
optifine = ""

[override]                 # 留空表示继承全局
java = "temurin-21"
memory_mb = 6144
jvm_args = "-XX:+UseG1GC"
resolution = [1920, 1080]
fullscreen = false

[meta]
created = 2025-10-31T17:00:00Z
last_played = 2025-10-31T19:00:00Z
play_seconds = 453600
favorite = true
```

写入方式：先写 `*.tmp` 再原子 rename；每个文件带 `schema_version`，用迁移函数链升级。

## 5. 核心子系统

### 5.1 下载引擎（`fg-net`）

- 源选项：自动 / 官方优先 / 镜像优先；版本列表与文件下载分开设置。
- 镜像前缀表数据驱动（`fg-net/src/mirrors.toml`，支持热更新）：Mojang → BMCLAPI；资源 → `/assets`；库 → `/libraries`；Forge/NeoForge/Fabric/Quilt 各自 maven；Modrinth/CurseForge 走 MCIM。
- 候选源：每个文件生成有序 `Candidate` 列表；CN 区域镜像优先，否则官方优先；连续失败 3 次熔断 5 分钟。
- 并发：全局信号量 `min(cpu×4, 64)`；按主机二级限流（BMCLAPI 32、Modrinth/CurseForge 16）；reqwest 连接池 + HTTP/2。
- 可靠性：每候选重试 5 次（200ms×2ⁿ，上限 5s）；`.part` + `Range`/`If-Range` 续传；边下边校验 SHA-1；条件请求；`fsync` 后 rename。
- 大文件（>16 MB）在支持 `Accept-Ranges` 时 4 段并行；资源对象等海量小文件合并进度事件。

### 5.2 任务系统（`fg-task`）

- DAG：节点 + 依赖边，同层并行、拓扑序执行；失败策略 `FailFast` / `ContinueOthers`。
- `Task`：`name`（i18n key）、`weight`、`run(TaskCx)`；`TaskCx` 提供进度、取消令牌、子任务、日志 span。
- 暂停 = 取消 + 可恢复标记，依赖断点续传；未完成图序列化到 `cache/tasks/*.json`。
- 进度聚合 `Σ(weight × fraction) / Σweight`；速度用 1 秒滑动窗口。

### 5.3 原版安装（`fg-game`）

1. 拉取 `version_manifest_v2.json`（条件请求 + 缓存）。
2. 下载 `versions/<id>/<id>.json` 并校验。
3. 解析：`inheritsFrom` 递归合并（子覆盖父、libraries 子在前、arguments 拼接）；按 OS/arch/features 过滤 rules；处理新旧 natives 格式；log4j 配置（1.7–1.18.1 强制修复配置）。
4. 下载 assetIndex；`virtual`/`map_to_resources` 老版本生成对应布局。
5. 并行下载 libraries 与 assets；写入 `instance.toml`，标记安装完成。
6. 资源补全：重算清单，只补缺失/哈希不符文件；默认只比大小与 mtime，严格模式算哈希。

### 5.4 加载器（`fg-loader`）

| 加载器 | 版本列表 | 安装方式 |
| --- | --- | --- |
| Fabric | `meta.fabricmc.net/v2/versions/loader/<mc>` | profile json 作为 `inheritsFrom` 子版本 |
| Quilt | `meta.quiltmc.org/v3/versions/loader/<mc>` | 同 Fabric |
| NeoForge | `maven.neoforged.net` maven metadata | installer jar + processors |
| Forge 1.13+ | BMCLAPI / promotions | installer、`install_profile.json`、processors |
| Forge 1.5–1.12 | 同上 | 旧 installer 的 `versionInfo` + universal jar |
| OptiFine | BMCLAPI `/optifine/<mc>` | 自带 Patcher/LaunchWrapper；与 Forge 共存时放 mods |

进入 M5 的细节：`data` 变量替换、`{SIDE}` 等内置变量、只跑 client processor、`DOWNLOAD_MOJMAPS` 自处理、outputs 哈希校验、原版 jar 临时副本。

### 5.5 Java 运行时（`fg-java`）

- 发现：`PATH`、`JAVA_HOME`；Windows 注册表与常见目录；macOS `/usr/libexec/java_home` 与 Homebrew；Linux `/usr/lib/jvm`、SDKMAN；HMCL/PCL/Prism 的 runtime（只读）；启动器 `runtimes/`。
- 解析：优先 `<home>/release`；缺失时 `java -XshowSettings:properties -version`（5s 超时）；Windows 读 PE 头、macOS 读 Mach-O 头判断架构。
- 匹配：优先 version json 的 `javaVersion.majorVersion`；缺失时按版本推断（≤1.16.5→8，1.17→16，1.18–1.20.4→17，1.20.5+→21）。精确优先，其次最近的高/低 major；不强制，先尝试运行，缺 Java 时才报 `error.java.none_installed` 并引导下载（M6）。
- 内存：自动策略按总内存/可用内存/模组数给建议；上限为物理内存减 2 GB（Android 另算）；预设 G1GC、ZGC Generational、Aikar flags。

### 5.6 启动流程（`fg-game::launch`）

1. 预检：账户、Java、内存、游戏目录可写。
2. 资源补全（可关闭）。
3. 解压 natives 到 `shared/natives/<hash>/`，遵守 `extract.exclude`；1.19+ 用 `-Djava.library.path` / `-Dorg.lwjgl.librarypath`。
4. 构建 classpath：去重（同 `group:artifact` 取最后版本，加载器库优先）+ 主 jar；分隔符按平台。
5. 拼参数：新旧 `arguments` 格式、全部占位符、`-Xmx/-Xms`、UTF-8 编码参数、macOS `-XstartOnFirstThread`、log4j 修复、authlib-injector。
6. 命令过长时写 `@argfile`（Java 9+）或 manifest classpath（Java 8）。
7. 启动子进程：`tokio::process::Command`，`current_dir = 实例游戏目录`；Windows `CREATE_NO_WINDOW`，Linux 可选 `gamemoderun`/独显变量。
8. 日志：逐行读 stdout/stderr，识别 log4j XML 事件并转结构化；批量推 `game.line`。
9. 窗口行为：检测游戏窗口出现后按设置隐藏/最小化；退出后恢复。
10. 退出：非 0 退出码或新 crash-report 时跑崩溃分析（M9），提供诊断包导出。
11. 统计：累加 `play_seconds`、更新 `last_played`。

Android 的启动实现是第二阶段：`fg-game::launch` 抽象出 `Launcher` trait，Android 生成启动描述 JSON，交给 `:game` 进程，见 §6。

### 5.7 账户（`fg-auth`）

- 离线：UUID v3（`OfflinePlayer:<name>`），可选自定义 UUID、本地皮肤。
- Yggdrasil / authlib-injector：ALI 发现、`authenticate/refresh/validate/invalidate/signout`、`availableProfiles` 选角色、皮肤查询与上传、authlib-injector jar 下载校验、LittleSkin 预设、拖入链接。
- 存储：`accounts.json` 只放元数据；令牌进系统凭据库（keyring / Android Keystore）。
- Microsoft：M11；数据模型预留 `AccountKind::Microsoft`。

### 5.8 内容与实例（`fg-content` / `fg-world`）

- 模组解析：Fabric/Quilt/Forge/NeoForge/旧 Forge/LiteLoader 元数据，只读 jar 内条目；图标缓存。
- 启用/禁用：`*.jar.disabled`（与 HMCL/PCL/Prism 兼容）。
- 更新检查：Modrinth `version_files/update`（sha1）、CurseForge fingerprints（murmur2），缓存 6 小时。
- 资源包/光影：`pack.mcmeta`、`options.txt`、`iris.properties`。
- 世界：`level.dat`、`icon.png`、`session.lock`、zip 备份与保留策略。
- 原理图：`.litematic` / `.schem` / `.nbt` 元数据。
- 文件监听：`notify` + 500ms 去抖。
- Modrinth / CurseForge：只走 MCIM 镜像访问 CurseForge；镜像不可用时置灰并提示；murmur2 指纹；依赖解析与安装计划。

### 5.9 日志与崩溃（`fg-diag`）

- 数据源：最近 stdout、`logs/latest.log`、`crash-reports/*.txt`、`hs_err_pid*.log`。
- 规则：`fg-diag/rules/*.toml`，字段 `id` / `pattern` / `source` / `captures` / `message_key` / `fix`。
- 首批规则：Java 版本、内存、显卡/OpenGL、文件损坏、模组缺依赖/重复/冲突、Mixin、OptiFine 冲突、加载器重复、ID 超限、tick 崩溃。
- 输出：按置信度排序，附模组与一键修复动作。

### 5.10 整合包与服务器

- 导入：Modrinth `.mrpack`、CurseForge `.zip`、MultiMC/Prism、HMCL、直接拖入 `.minecraft`。
- 导出：`.mrpack` 优先，可选目录；更新整合包做增量。
- 服务器：`servers.json` + `servers.dat` 双向同步；SLP Ping（1.7+ 与旧协议）、SRV、局域网组播发现、Quick Play。
- 联机：M12+（EasyTier 类方案）。

## 6. Android

### 6.1 启动器 UI

与桌面共用 `commonMain`：底栏、主页、实例库、下载、设置、实例设置、安装流程全部一致。Android 薄壳只负责：

- `MainActivity`：`enableEdgeToEdge`、状态栏/导航栏图标、动态色、首帧前读设置。
- manifest、启动图标、`jniLibs/<abi>/libfg_ffi_jni.so`。
- 数据根：`getExternalFilesDir(null)`；外部 `.minecraft` 导入用应用内目录浏览（SAF 的 URI 不能直接交给 Rust）。
- 通知、前台服务、Keystore 等后续能力。

### 6.2 游戏运行时（第二阶段）

Android 上跑 Java 版需要：内置交叉编译的 OpenJDK（Pojav/Amethyst 的 `android-openjdk-build`）、LWJGL Android 移植、GLES/Vulkan 转译层（GL4ES/Zink/MobileGlues）、触控映射、`:game` 独立进程与 `ANativeWindow`、低内存设备策略。这些不自研。

- `fg-game::launch` 抽象 `Launcher` trait：桌面 = 子进程；Android = 生成启动描述（classpath、JVM 参数、游戏参数、渲染器、环境变量）交给 `:game`。
- `fg-java`：Android 只用内置 JRE，不做扫描；8/17/21 按需下载，不全部打进 APK。
- 存储：私有目录；导入导出走 SAF；不申请 `MANAGE_EXTERNAL_STORAGE`。
- 分发：Google Play 禁止下载并执行可执行代码，Android 版走 GitHub Release / 国内商店。

### 6.3 Android 分步骤

| 步骤 | 内容 | 验收 |
| --- | --- | --- |
| A0 | 评估 Amethyst，确定复用组件与许可 | 评估报告 + 许可清单 |
| A1 | Compose 外壳接入现有 `fg-*`（本次重构已完成 UI 部分） | 浏览/安装/启动错误分支 |
| A2 | Android 平台层骨架 | 双向调用 demo |
| A3 | 内置 JRE 17/21 | 设备上 `java -version` 通过 |
| A4 | GameActivity + 渲染器 | 1.21.4 进主菜单 |
| A5 | 触控层 | 能玩生存 |
| A6 | Fabric/Quilt 优先，Forge/NeoForge 后台安装 | Sodium 包可运行 |
| A7 | GPU 适配表与回退 | 设置页可选渲染器 |
| A8 | 账户、整合包、世界备份、服务器 | 与桌面对齐（除 Java 管理） |
| A9 | 性能与发热 | 30 分钟稳定 |
| A10 | 签名与分发 | Android 预览版 |

## 7. 里程碑

### 7.1 已完成

M0–M4 见 §1.1。M4 的实例管理、安装、启动链路已经在 macOS / Windows（代码级）/ Linux / Android 上验证或接线；本次重构不改变 Rust 核心与 RPC 契约。

### 7.2 重构阶段（R0–R6）

每一步单独可合并、可回滚。三个桌面原生客户端已在 2026-11-13 删除；旧 Android 客户端在 R2 迁移完成后只作参照保留，R6 删除。

| 阶段 | 内容 | 工期 | 验收 |
| --- | --- | --- | --- |
| **R0 技术验证** ✅ | 新建 `apps/compose` 三模块；Kotlin 2.4 / CMP 1.12 / AGP 9 / Gradle 9.3 / JDK 21；桌面 JNI 加载；`app.snapshot` 打通；macOS DMG 打包 | 完成 2026-11-12 | Android 模拟器与 macOS 应用均显示核心数据；基线见 §7.4 |
| **R1 共享层** ✅ | JSON 层迁 kotlinx.serialization；`CoreNative`/`AppModel` 去 Android 化；抽 9 个平台接口；单测迁 `commonTest` | 完成 2026-11-13 | `commonMain` 无 `android.*` / `java.*`；桌面经 JNI 列出实例 |
| **R2 UI 迁移** ✅ | theme/components/home/library/downloads/settings/instances 逐页搬；字符串与图片资源化；Material3 API 差异处理 | 完成 2026-11-13 | 四页 + 3 个 sheet 渲染（含离屏截图测试）；Android 模拟器 26.3 页面回归 |
| **R3 Android 切换** ✅ | manifest/res/jniLibs/Activity 迁入薄壳；构建脚本与 CI 更新 | 完成 2026-11-14 | `assembleDebug` + 模拟器安装启动（Android 15 模拟器，冷启动 1.9–2.5 s） |
| **R4 桌面外壳** ✅ | 窗口状态持久化、菜单与快捷键、Esc 返回、JFileChooser/SAF 目录选择、≥840 dp NavigationRail | 完成 2026-11-14 | `:desktop:test` 覆盖两条导航形态与两档窗口尺寸 |
| **R5 打包与 CI** | jpackage、图标、JRE/jlink、DMG/MSI/DEB、签名与公证、CI 矩阵 | 4–6 天 | 干净机器安装可用；三 OS + Android CI 绿 |
| **R6 清理** ✅ | 删除旧 `apps/android`；更新 README/design/backlog；tag 流程见 `docs/release.md` | 完成 2026-11-14 | `cargo test --workspace` + Gradle 构建通过 |

合计约 25–36 人天（一人全职 5–7 周，加 20% buffer 约 6–8 周）。R0–R4 与 R6 已通过（R0 基线见 §7.4），剩余 R5（打包、签名、三 OS CI 矩阵的签名部分）。

### 7.3 后续核心里程碑

| 里程碑 | 内容 | 约工期 |
| --- | --- | --- |
| M5 | Fabric/Quilt/NeoForge/Forge/OptiFine 安装器 | 2–3 周 |
| M6 | Java 运行时自动下载与管理页 | 1 周 |
| M7 | Yggdrasil / authlib-injector、账户页、皮肤 | 1 周 |
| M8 | 模组、资源包、光影、世界、原理图、下载页六类资源 | 3 周 |
| M9 | 整合包、服务器、崩溃分析 | 2 周 |
| M10 | 设置、i18n、自动更新、签名、发布打磨 | 2 周 |
| M11 | 微软正版登录（审核通过后） | 1 周 |
| M12+ | Android 游戏运行时（A0–A10）、联机、更多加载器 | 3–4 个月 / 待定 |

M5+ 只改 Rust 核心与共享 UI，不再有"每端各接一次"的工作。

### 7.4 P0/R0 实测基线

> 2026-11-12 首测：macOS 27 arm64、JDK 21、Compose Desktop 1.12.1；Windows/Linux 在 R5 的 CI 或本地补测。

| 指标 | 目标 | 实测（2026-11-12，macOS 27 arm64 / JDK 21） |
| --- | --- | --- |
| macOS DMG 体积 | ≤ 100 MB | 71 MB |
| 安装后体积 | ≤ 160 MB | 132 MB |
| 冷启动到首帧 | ≤ 2.5 s | 1.61 s（进程启动到首次组合） |
| 空闲 RSS | ≤ 300 MB | 196 MB（启动 8 s 后） |
| 中文输入法 | 可用 | 页面已迁入 `commonMain`，待三 OS 人工回归（R5） |
| Android APK 体积 | 与现状相当（±15%） | 24.3 MB（debug，arm64 + x86_64，2026-11-14 复测） |

## 8. 风险与待决问题

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| JVM 体积/启动/内存不达标 | 高 | R0 实测；jlink 裁剪 + AppCDS + ZGC；不达标则评估回退（见 §1.3） |
| 桌面中文输入法 | 高 | R0 在三 OS 的 `TextField` 实测；必要时调 Skiko IME 参数或等待上游 |
| macOS 签名/公证 + JRE + dylib | 中 | JBR 21；dylib 一并签名；`notarytool` 脚本化；预留 2 天 |
| Windows MSI/WiX/SmartScreen | 中 | 固定 WiX 版本；EV/OV 证书；v1 不做 Mica |
| Linux 字体/GL/Wayland | 中 | 包内 Noto Sans SC；X11/XWayland 基线；声明依赖 |
| 桌面无障碍弱于原生 | 中 | Compose 语义 + 键盘导航；发布前人工测试；v2 补 |
| AGP 9 / Kotlin 2.4 迁移断裂 | 中 | 新工程独立；旧工程分支升级；CI 双跑 |
| 微软正版登录审核 | 中 | 现在注册 Azure 应用并提交审核，M11 接入 |
| CurseForge 依赖 MCIM | 中 | 可用性探测与降级提示；必要时自建代理 |
| PCL 许可边界 | 低 | 不参考 `Plain Craft Launcher 2/`，只用 PCL.Core |
| Apple 芯片旧版本 natives | 中 | 自带 LWJGL arm64 natives 或 x86_64 Java + Rosetta |
| Android 复用组件许可 | 中 | A0 输出清单（JRE、LWJGL、渲染器） |
| 浅色令牌未定稿 | 低 | 等 pen 浅色画面；先按现有令牌 |

## 9. 测试策略

- Rust 单元测试：version json 合并、rules、参数构建、镜像替换、murmur2、NBT、崩溃规则；fixtures 在 `crates/*/tests/fixtures/`。
- 集成测试：`wiremock` 模拟 Mojang / BMCLAPI / Modrinth / Yggdrasil；下载故障注入。
- 端到端：`fg-cli` 在 CI 三平台安装并启动（Linux 用 `xvfb-run`），检查日志关键行。
- FFI 契约：C ABI 与 JNI 各一套最小调用测试；JSON 字段名与 `bindings/README.md` 对齐，防止漂移。
- Compose 测试：
  - `commonTest`：`CoreWire`、`InstanceFormat`、`LibraryQuery`、`AppModel` 状态机。
  - `desktopTest`：Compose UI test（四条导航、打开 sheet、主题切换）。
  - Android：Compose UI test + 启动冒烟（无 Java 时验证错误分支）。
- 视觉：每个平台截关键页面；与 `design/screens`/pen 对照，不要求像素一致。
- 打包：三 OS 干净机器（或 CI 容器/VM）安装、启动、卸载；签名与公证单独验收。

## 10. 页面与后端能力对照

| 页面 | 主要 FFI 操作 / 事件 | 依赖模块 |
| --- | --- | --- |
| 主页 | `app.snapshot`、`instances.summary`、`launch.start`、`tasks.list`、`versions.news` | fg-game、fg-net |
| 实例库 | `instances.list`、`instances.create`、`instances.import`、`instances.favorite` | fg-game |
| 实例设置 | `instances.settings.get/set`、`instances.verify`、`instances.dir` | fg-core、fg-java |
| 下载 / 安装 | `versions.list`、`version.plan`、`install.start`、`tasks.cancel`、`task.progress` | fg-net、fg-task、fg-game |
| 游戏会话 | `launch.start`、`launch.status`、`session.wait/stop`、`game.line` | fg-game |
| 设置 | `gameConfig.get/set`、`app.snapshot` | fg-core |
| 服务器（M9） | `servers_list`、`server_ping`、`lan_discover` | fg-world |
| 账户（M7） | `account_*`、`yggdrasil_discover`、`skin_upload` | fg-auth |
| 内容（M8） | `content_search`、`content_versions`、`content_install` | fg-content |
| 诊断（M9） | `game.log.recent`、`crash_analyze`、`instance_export` | fg-diag |

## 11. 许可与参考边界

- 本仓库 GPL-3.0-only。新增依赖必须兼容 GPL-3.0（MIT / Apache-2.0 / BSD / LGPL 均可）。
- HMCL（GPL-3.0）可参考实现细节；PCL.Core（Apache-2.0）可参考并保留 NOTICE；`Plain Craft Launcher 2/` 只可看协议层思路，不移植代码。
- Animeko（AGPL-3.0）只参考工程结构与打包经验，不复制代码。
- 协议知识以官方文档 / wiki.vg / minecraft.wiki 为准。
