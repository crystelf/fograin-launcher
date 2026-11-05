# 雾雨启动器（Fograin Launcher）设计文档

> 状态：草案 v0.2 · 2026-10-31（原生 UI 架构）
> 范围：Minecraft Java 版启动器，**Rust 核心 + 四个原生 UI 客户端**：macOS（SwiftUI + AppKit）、Windows（C# + WinUI 3）、Linux（Rust + GTK4/libadwaita）、Android（Kotlin + Compose）。桌面三端为第一阶段，Android 为第二阶段。

## 0. 目标与非目标

目标

- 安装、管理、启动 Minecraft Java 版的全部版本（含快照、远古版本）。
- 加载器：Fabric、Quilt、Forge、NeoForge、OptiFine（LiteLoader、LegacyFabric、Cleanroom 视需求再加）。
- 实例（版本隔离）管理：模组、资源包、光影、世界、原理图、日志与崩溃报告、导出与备份。
- Modrinth / CurseForge 资源浏览与安装，整合包导入导出。
- Java 运行时自动匹配与下载。
- 账户：离线、LittleSkin、authlib-injector（Yggdrasil 外置登录）。
- 服务器列表（收藏、局域网发现、状态 Ping）。
- 国内网络友好：BMCLAPI 等镜像源、自动回退。
- 以 `fograin.pen` 的布局、信息架构和视觉语言为参考，由四个平台原生客户端实现；**不做 WebView + CSS 皮肤**。平台原生控件、窗口行为和交互优先于逐像素复刻。

非目标（第一阶段）

- 微软正版登录（见 §12「待决问题」，延后到 M11）。
- 基岩版、iOS。
- 内置联机（EasyTier / Terracotta 类方案），列入 M12 以后。

## 1. 参考源码与许可边界

| 来源 | 许可 | 用法 |
| --- | --- | --- |
| `temp/HMCL`（HMCLCore） | GPL-3.0 | 与本仓库许可一致，可参考实现细节（下载候选源、Forge processors、崩溃规则等），在关于页致谢 |
| `temp/PCL-CE/PCL.Core` 等 | Apache-2.0 | 可参考实现（Java 扫描、Yggdrasil 客户端、McPing、ModDependencyResolver 等），保留 NOTICE 与署名 |
| `temp/PCL-CE/Plain Craft Launcher 2/` | 《PCL 分发有限许可》 | 不移植代码与算法。重度参考会要求产品名以 PCL 开头、主色必须为蓝色，与雾雨品牌冲突。只看公开协议层面的思路 |

规则：

- 所有协议级知识（Mojang 版本清单格式、Yggdrasil、Modrinth API）以官方文档 / wiki.vg / minecraft.wiki 为准，源码只作对照。
- 内存自动分配、崩溃识别规则自研，规则数据独立成文件。
- 本仓库保持 GPL-3.0。各平台 UI 依赖须兼容 GPL-3.0（MIT / Apache-2.0 / BSD 均可）。

## 2. 技术栈

| 层 | 选型 | 说明 |
| --- | --- | --- |
| 核心 | Rust stable，tokio 多线程运行时 | `fg-*` crate，包含下载、安装、Java、账户、实例、启动等全部业务逻辑 |
| 边界 | `fg-ffi`：UniFFI + 稳定 C ABI | Swift/Kotlin 用 UniFFI，C# 用 P/Invoke；Linux Rust 可直接依赖 crate |
| macOS UI | Swift + SwiftUI，AppKit 仅做窗口/材质/菜单桥 | 原生红绿灯、`NSVisualEffectView`、原生菜单与快捷键 |
| Windows UI | C# + WinUI 3（Windows App SDK） | 原生标题栏、Mica、Snap Layout、`NavigationView` |
| Linux UI | Rust + GTK4 + libadwaita | 原生 `Adw.HeaderBar`、Adwaita 控件、GApplication |
| Android UI | Kotlin + Jetpack Compose + Material 3 | Compose 页面、`GameActivity` 独立进程 |
| HTTP | reqwest + rustls | 关闭 native-tls，避免 Linux 上的 OpenSSL 依赖 |
| 序列化 | serde / serde_json / toml | |
| 压缩 | zip、flate2 | jar、natives、整合包、NBT(gzip) |
| NBT | fastnbt | servers.dat、level.dat |
| 哈希 | sha1、sha2、md-5、自实现 murmur2 | murmur2 用于 CurseForge 指纹 |
| 日志 | tracing + tracing-appender | 滚动文件日志；各原生 UI 通过 FFI 订阅日志/崩溃事件 |
| 设计令牌 | `design/tokens.json` → 各平台常量 | 单一来源；不手写十六进制颜色 |
| 3D 皮肤预览 | 各平台原生 3D API | macOS SceneKit、Windows D3D11、Linux `GtkGLArea`、Android GLES；待原型确认 |

依赖版本由 `Cargo.toml` 和 `Cargo.lock` 锁定；各原生 App 锁定自己的 SDK/Gradle/Windows App SDK 版本。

## 3. 总体架构

架构决策记录见 [ADR 0001](adr/0001-native-ui-clients.md)：Rust 核心 + 四个原生 UI 客户端。

```text
fograin-launcher/
├─ Cargo.toml                 # Rust workspace：crates/* + bindings/fg-ffi
├─ crates/
│  ├─ fg-core/                # 模型、路径、配置、错误（无 UI、无异步运行时）
│  ├─ fg-net/                 # HTTP、镜像、下载引擎、缓存
│  ├─ fg-task/                # 任务图、进度、取消、持久化
│  ├─ fg-game/                # 版本清单、安装器、启动参数
│  ├─ fg-loader/              # Fabric / Forge / NeoForge / OptiFine
│  ├─ fg-java/                # Java 扫描、运行时下载
│  ├─ fg-auth/                # 离线、Yggdrasil、authlib-injector
│  ├─ fg-content/             # Modrinth、CurseForge、模组、整合包
│  ├─ fg-world/               # NBT、服务器列表、世界备份
│  ├─ fg-diag/                # 日志、崩溃分析
│  └─ fg-cli/                 # 无 UI 的调试/CI 入口
├─ bindings/
│  ├─ fg-ffi/                 # C ABI / UniFFI 边界；唯一 UI 入口
│  └─ README.md               # 契约与生成说明
├─ apps/
│  ├─ macos/                  # SwiftUI + AppKit；Swift Package / Xcode
│  ├─ windows/                # C# + WinUI 3；.csproj
│  ├─ linux/                  # Rust + GTK4/libadwaita；独立 Cargo workspace
│  └─ android/                # Kotlin + Compose；Gradle
├─ design/
│  ├─ tokens.json             # 颜色/字号/间距/圆角/窗口尺寸
│  ├─ assets/                 # 位图资源
│  └─ screens/                # pen 导出参考
├─ docs/
└─ .github/workflows/
```

分层原则：

- `fg-*` 不依赖任何 UI 框架。UI 只能通过 `fg-ffi` 调用；Linux GTK 客户端作为同一语言的特例，可以直接依赖 `fg-*`，但仍不得把 GTK 类型带入核心 crate。
- 四个 UI 客户端**不共享 UI 代码**，只共享 Rust 核心、FFI 契约、设计令牌和测试夹具。
- 每个客户端独立拥有自己的窗口、导航、列表、菜单、快捷键、无障碍和动画；不模拟其他平台的窗口控件。
- 版本号、发布节奏和 i18n 资源可以统一；界面布局按平台适配。

### 3.1 FFI 契约

- 操作按领域分组：`instance_*`、`download_*`、`java_*`、`account_*`、`launch_*`、`content_*`、`server_*`、`settings_*`。
- 长任务返回 `TaskId`；进度通过每帧/节流回调推送，不在 UI 线程阻塞。
- 事件：`task.progress`、`task.state`、`game.log`、`game.exit`。原生客户端把它们转成自己的状态容器。
- 大列表（模组、资源、世界）分页返回；图标/缩略图通过缓存文件路径返回，不把二进制塞进 FFI。
- 错误统一为 `fg_core::Error { kind, message_key, detail }`；UI 按 `message_key` 做本地化。
- ABI 版本从 `fg_abi_version()` 读取；破坏性变更必须升版本，四个客户端同时升级。
- 不把 UI 类型（颜色、字体、控件状态）放进 FFI。

## 4. 原生 UI 与平台适配

设计稿 `fograin.pen` 有 54 个画面：桌面 3 套布局 × 13 个画面，Android 15 个画面。它只回答两件事：**这个页面有哪些元素**，以及**这些元素如何分组、排序**。它不是像素稿，不是控件规范，也不是要在四个平台复刻的 CSS。

- 控件一律使用平台原生实现。macOS 使用 `NSToolbar`、`NSSearchField`、`NSSegmentedControl`、`NSMenu`、`NSButton`、`Table`/`List` 等；Windows 使用 WinUI 3 对应控件；Linux 使用 GTK4/libadwaita；Android 使用 Material 3。
- 不要用自绘胶囊、玻璃块或假按钮去模仿 pen 的视觉。原生控件的尺寸、材质、焦点环、键盘行为、输入法和无障碍语义优先于笔稿像素。
- 页面层级、信息密度和操作路径保持一致；控件外观按平台调整。视觉验收看关键页面截图，不要求像素一致。
- pen 中的 `Glass Group`、`Search Field` 等图层名只描述功能位置，不规定实现方式。例如 macOS 顶部搜索用原生搜索框，视图切换用原生 segmented control，排序用原生菜单。
- 只有在平台控件无法表达某个内容型视觉（例如封面图、状态胶囊、实例图标）时才自绘，并且不把自绘结果伪装成系统控件。
- macOS 客户端最低 macOS 14。顶部动作组使用原生 `NSToolbarItemGroup` 胶囊（26/27 由系统渲染 Liquid Glass，14/15 渲染原生分组胶囊）；通过 `#if compiler(>=6.2)` 与 `#available(macOS 26.0, *)` 双重判断，旧 Xcode/SDK 也能构建；Liquid Glass 分支需要 Xcode 26+（Swift 6.2）才会编译进二进制。

### 4.1 设计令牌

`design/tokens.json` 是唯一来源，生成各平台常量：

- Swift：`GeneratedTokens.swift`
- Kotlin：`Tokens.kt`
- C#：`DesignTokens.cs`
- Rust/GTK：`tokens.rs`

内容：

- 桌面暗色基色：`background #1F1F22`、`surface #242426`、`textPrimary #F5F5F7`、`textSecondary #EBEBF599`、`textTertiary #EBEBF55C`、`separator #FFFFFF14`。
- 平台强调色：macOS `#0A84FF`、Windows 11 `#60CDFF`、GNOME `#3584E4`、Android M3 `#8ED5AE`（深色）/ `#1B6B4A`（浅色）。
- 字体使用系统字体：SF Pro（macOS）、Segoe UI Variable（Windows）、Adwaita Sans（GNOME）、Roboto（Android）。品牌字可单独打包，但正文不再打包 Inter。
- 浅色令牌仍是初稿，等 pen 补浅色画面后由四个平台共同更新。

### 4.1.1 深浅色主题

- 设置项 `appearance.theme = "system" | "light" | "dark"`，默认 `system`。
- 统一由 Rust 核心读取 `launcher.toml`，在创建窗口前通过 FFI 暴露给 UI；各客户端在首帧前设置 `colorScheme` / `RequestedTheme` / `AdwColorScheme`，避免闪烁。
- 跟随系统：
  - macOS：SwiftUI `@Environment(\.colorScheme)` + `NSApp.effectiveAppearance`。
  - Windows：`Application.RequestedTheme` + `UISettings.ColorValuesChanged`。
  - Linux：`AdwStyleManager` / `GtkSettings:gtk-application-prefer-dark-theme`。
  - Android：`Configuration.uiMode` + `isSystemInDarkTheme()`；可选 Material You 动态色。
- 手动切换时同时设置原生窗口外观（macOS `NSAppearance`、Windows `AppWindow`/DWM、GTK `AdwStyleManager`、Android `AppCompatDelegate`），不需要重启。
- 原生窗口材质、红绿灯/标题栏、菜单都跟随主题，不再有 CSS 令牌同步。

### 4.2 每个平台的原生能力

| 能力 | macOS | Windows 11 | Linux GNOME | Android |
| --- | --- | --- | --- | --- |
| 窗口材质 | SwiftUI Material / `NSVisualEffectView` | WinUI 3 `MicaBackdrop` / Acrylic | libadwaita 表面；无系统模糊 | M3 surface / elevation |
| 标题栏 | 原生 `NSWindow` 红绿灯；首版用原生标题栏，后续再评估内容延伸 | 原生 `AppWindowTitleBar`；需要时自定义 | `Adw.HeaderBar` | Material 3 `TopAppBar` |
| 窗口拖拽 | 原生标题栏 + SwiftUI | 原生标题栏 | 原生 headerbar | 系统状态栏/手势 |
| Dock/任务栏进度 | `NSDockTile` | `ITaskbarList3` | Unity LauncherEntry DBus（可用时） | 前台服务通知 |
| 通知 | `UNUserNotificationCenter` | Windows App SDK AppNotification | `GNotification` / libnotify | `NotificationChannel` |
| 凭据存储 | Keychain | Credential Manager | libsecret / Secret Service | Android Keystore |
| 文件选择 | `NSOpenPanel` | WinUI FileOpenPicker / Win32 | GTK `FileDialog` / portal | SAF |
| 单实例/深链接 | `NSApplicationDelegate` | AppInstance / 协议注册 | `GApplication` | Intent / deep link |
| 自动更新 | Sparkle 或自建签名更新 | WinAppSDK/MSIX 或自建 | AppImage 自更新 / 包管理器 | APK 更新检查 |
| GPU 信息 | Metal / IOKit | DXGI | sysfs / `lspci` | GLES / Vulkan 扩展 |
| 游戏进程 | 子进程 | 子进程 | 子进程 | `:game` 独立进程 + JNI |

### 4.3 平台适配代码

- macOS：`apps/macos/Sources/Fograin/Platform/`，AppKit 桥接（窗口、材质、Dock、菜单、文件面板）。
- Windows：`apps/windows/Fograin.Windows/Platform/`，Win32/WinUI 互操作（Mica、任务栏、Snap Layout、文件选择）。
- Linux：`apps/linux/src/platform/`，GTK/Adwaita、GApplication、libsecret、portal。
- Android：`apps/android/app/src/main/java/com/fograin/launcher/platform/`，SAF、Keystore、通知、GameActivity。
- 共享的纯 Rust 查询（内存、CPU、GPU 命令解析）可以放进 `fg-diag` 或后续 `fg-sys`；平台句柄不跨 FFI。

### 4.4 原生 UI 差异

- 不再有 WebView、CSS 皮肤、`data-platform`、Tailwind、React 组件库。
- 图标使用平台原生集合：SF Symbols / Fluent Icons / Adwaita symbolic / Material Symbols。
- 控件必须使用平台原生焦点、键盘、输入法、拖放和无障碍语义；不要用自绘控件模拟。
- 布局允许因平台控件尺寸不同而调整，但页面层级、信息密度和操作路径保持一致。
- 视觉验收：每个平台对关键页面截原生窗口图，与 pen 的布局参考比对；不要求像素级一致。

## 5. 数据目录与配置

### 5.1 目录布局

根目录（可在设置中更改）：

- macOS `~/Library/Application Support/Fograin`
- Windows `%APPDATA%\Fograin`
- Linux `$XDG_DATA_HOME/fograin`（默认 `~/.local/share/fograin`）
- Android：应用私有目录 `/data/data/<pkg>/files`，可选外部 `Android/data/<pkg>/files`

```
<root>/
├─ launcher.toml              # 全局设置
├─ accounts.json              # 账户元数据（不含令牌，令牌在系统凭据库）
├─ servers.json               # 启动器级服务器收藏
├─ shared/                    # 多实例共享（设计稿设置页「多个实例共享资源文件」）
│  ├─ versions/<id>/<id>.json, <id>.jar
│  ├─ libraries/              # Maven 布局
│  ├─ assets/{indexes,objects,log_configs,virtual/legacy}
│  └─ natives/<instance-hash>/
├─ instances/<instance-id>/
│  ├─ instance.toml           # 实例元数据与覆盖设置
│  ├─ .minecraft/             # 游戏工作目录（mods、resourcepacks、shaderpacks、saves、schematics、config、logs、crash-reports、options.txt、servers.dat）
│  └─ icon.png
├─ runtimes/<vendor>-<version>-<arch>/   # 启动器管理的 Java
├─ cache/
│  ├─ sha1/<aa>/<sha1>        # 内容寻址缓存，所有下载按 SHA-1 去重
│  ├─ http/                   # ETag / Last-Modified 元数据缓存（清单、API 响应）
│  └─ icons/
├─ backups/<instance-id>/<timestamp>.zip
└─ logs/launcher-YYYY-MM-DD.log
```

说明：

- 「共享资源」模式下 `versions`、`libraries`、`assets` 只存一份，实例只放自己的游戏目录。关闭共享时，每个实例的 `.minecraft` 自带完整目录，方便整体拷走。
- 支持“导入外部 .minecraft 目录”：把它登记为只读的外部仓库，不迁移文件。HMCL / PCL / 官方启动器的现有目录都能直接识别。
- 内容寻址缓存借鉴 HMCL `DefaultCacheRepository`：下载前先按 SHA-1 查缓存，命中则硬链接或拷贝；BMCLAPI 响应头 `x-bmclapi-hash` 也可用于提前命中缓存（HMCL `FetchTask` 的做法）。

### 5.2 配置格式

- `launcher.toml`：分区 `[general] [game] [java] [download] [appearance] [network] [notify] [advanced]`，与设置页的 8 个分组一一对应。
- `instance.toml`：
  ```toml
  id = "01JBX..."            # ULID
  name = "雾林生存"
  icon = "icon.png"
  minecraft = "1.21.4"
  [loader]
  kind = "fabric"            # vanilla|fabric|quilt|forge|neoforge
  version = "0.16.9"
  optifine = ""              # 可与 forge 叠加
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
  source = { kind = "modrinth", project = "xxx", version = "yyy" }  # 整合包来源，用于「更新整合包」
  ```
- 配置读写：先写 `*.tmp` 再原子 rename，避免崩溃时写坏文件。每个文件带 `schema_version` 字段，用迁移函数链做升级。

## 6. 下载子系统（fg-net）

### 6.1 下载源与镜像规则

源选项：「自动」「官方优先」「镜像优先」，版本列表和文件下载分开设置（与 HMCL 相同的两个维度）。

镜像规则表（前缀替换，数据驱动，放在 `fg-net/src/mirrors.toml`，支持远程热更新）：

| 官方前缀 | BMCLAPI 替换 |
| --- | --- |
| `https://launchermeta.mojang.com`、`https://piston-meta.mojang.com`、`https://piston-data.mojang.com`、`https://launcher.mojang.com` | `https://bmclapi2.bangbang93.com` |
| `https://resources.download.minecraft.net` | `.../assets` |
| `https://libraries.minecraft.net` | `.../libraries` |
| `https://maven.minecraftforge.net`、`https://files.minecraftforge.net/maven` | `.../maven` |
| `https://maven.neoforged.net/releases` | `.../maven`（另有 `neoforged.forgecdn.net` 备用） |
| `https://meta.fabricmc.net` | `.../fabric-meta` |
| `https://maven.fabricmc.net` | `.../maven` |
| `https://meta.quiltmc.org` | `.../quilt-meta`（需验证） |
| `https://authlib-injector.yushi.moe` | `.../mirrors/authlib-injector` |
| `https://api.modrinth.com` / `cdn.modrinth.com` | MCIM 镜像 `https://mod.mcimirror.top/modrinth`、`https://mod.mcimirror.top`（回退） |
| `https://api.curseforge.com` / `edge.forgecdn.net` | 只走 MCIM：`https://mod.mcimirror.top/curseforge`、`https://mod.mcimirror.top`（不内置 API Key，不直连官方） |
| Maven Central | 腾讯云 Nexus 镜像 |

候选源生成（参考 HMCL `HMCLDownloadProvider.getCandidates`）：

- 每个文件生成有序的候选 URL 列表 `Vec<Candidate { url, retry, timeout }>`。
- 「自动」模式：区域判断为 CN（系统 locale + 时区 + 首次启动测速），则镜像在前、官方在后；否则只用官方，失败后再回退镜像。
- 「官方优先」：官方在前、镜像在后。「镜像优先」：镜像在前。
- 自动测速：启动时并行请求一个小文件（版本清单 HEAD），记录 RTT 和成功率，存进 `cache/source-health.json`。连续失败 3 次的源熔断 5 分钟，期间排到列表末尾。

### 6.2 下载引擎

核心类型：

```rust
pub struct DownloadRequest {
    pub candidates: Vec<Candidate>,
    pub dest: PathBuf,
    pub integrity: Option<Integrity>,   // Sha1 / Sha256 / Sha512 / Size
    pub size_hint: Option<u64>,
    pub cache: CachePolicy,             // ContentAddressed | HttpConditional | None
    pub priority: Priority,             // 清单 > 依赖库 > 资源对象 > 模组
}
```

并发与调度：

- 全局 `tokio::sync::Semaphore` 控制同时连接数，默认 `min(cpu × 4, 64)`（同 HMCL `FetchTask.DEFAULT_CONCURRENCY`），设置项可调 1–128。
- 按主机名二级限流：BMCLAPI 每主机不超过 32；Modrinth / CurseForge API 每主机 16（HMCL 两个 API 仓库同为 16）。
- reqwest 单一 `Client` 复用连接池，开启 HTTP/2，`pool_max_idle_per_host = 32`。
- 全局限速：令牌桶，作用在 `bytes_stream()` 读取循环上。
- 大文件（大于 16 MB，如游戏 jar、JDK 包）在服务器支持 `Accept-Ranges: bytes` 时分 4 段并行下载；小文件单连接。

可靠性：

- 每个候选源默认重试 5 次，连接与读取超时 10s；退避 200ms × 2ⁿ，上限 5s。全部候选失败才判失败（HMCL 的做法是逐候选、逐次重试）。
- 断点续传：写 `dest.part` 加旁路文件 `dest.part.meta { url, etag, last_modified, len }`。续传时带 `Range` 与 `If-Range`，返回 416 或 ETag 变化时从头下载。
- 校验：边下边算哈希，不对就删掉文件，换下一个候选源。
- 条件请求：清单、API 响应用 `If-None-Match` / `If-Modified-Since`，命中 304 直接读缓存。
- 写盘：`tokio::fs` + 64 KB 缓冲，完成后 `fsync` 再 rename。
- 文件数巨大的阶段（资源对象常有 3000–4000 个）合并进度事件，不按单文件推。

### 6.3 任务系统（fg-task）

参考 HMCL `Task` 的组合模型（`thenCompose`、`runSequentially`、依赖与被依赖任务）和 PCL.Core `App/Tasks`，在 Rust 里做成 DAG：

```rust
pub trait Task: Send {
    fn name(&self) -> Cow<str>;          // i18n key
    fn weight(&self) -> u32;             // 进度权重
    async fn run(&mut self, cx: TaskCx) -> Result<()>;
}
// TaskGraph：节点 + 依赖边；同层并行、拓扑序执行；任一失败按策略 FailFast / ContinueOthers
```

- `TaskCx` 提供：进度上报、取消令牌（`tokio_util::sync::CancellationToken`）、子任务派生、日志 span。
- 暂停：通过取消令牌加「可恢复」标记实现，依赖下载引擎的断点续传。下载页「全部暂停」就是对所有活动图发暂停。
- 持久化：未完成的安装任务图序列化到 `cache/tasks/*.json`，重启后可在下载页续上。
- 进度聚合：`Σ(weight × fraction) / Σweight`；速度用 1 秒滑动窗口。

## 7. 游戏安装（fg-game）

### 7.1 原版安装流程

1. 获取 `version_manifest_v2.json`（`piston-meta.mojang.com/mc/game/version_manifest_v2.json`），条件请求加缓存。下载页「版本动态」卡片也用它。
2. 下载 `versions/<id>/<id>.json`，按清单里的 sha1 校验。
3. 解析 version json：
   - `inheritsFrom` 递归合并（子覆盖父，`libraries` 子在前，`arguments` 拼接）。合并逻辑独立成 `resolve_version()`，加载器 json 也走这里。
   - `libraries[].rules` 按 OS 名（osx/windows/linux）、arch、`features` 过滤。
   - natives：旧格式 `natives` + `classifiers`（`${arch}` 替换为 32/64）；1.19+ 起直接以普通库形式出现、名字带 `natives-<os>` 分类器。
   - `downloads.client` 得到主 jar；`downloads.client_mappings` 只在 Forge 安装时需要。
   - `javaVersion.majorVersion`、`component` 用于 Java 匹配。
   - `logging.client` 下载 log4j 配置；对 1.7–1.18.1 强制附加修复后的配置，防 Log4Shell。
4. 下载 `assetIndex`。`virtual`/`map_to_resources` 的老版本需要把对象复制成 `assets/virtual/legacy` 或 `<instance>/resources` 的文件名布局。
5. 并行下载 libraries 与 assets 对象（任务图两个分支并行）。
6. 写入 `instance.toml`，标记安装完成。

「资源补全」（主页卡片、启动前校验）：重算上述清单，只下载缺失或哈希不符的文件。启动前校验默认只比对大小和修改时间，用户可开严格模式算哈希。

### 7.2 加载器安装（fg-loader）

| 加载器 | 版本列表 | 安装方式 |
| --- | --- | --- |
| Fabric | `meta.fabricmc.net/v2/versions/loader/<mc>` | 拉 `/v2/versions/loader/<mc>/<loader>/profile/json`，作为 `inheritsFrom` 子版本写入；库走 maven.fabricmc.net |
| Quilt | `meta.quiltmc.org/v3/versions/loader/<mc>` | 同 Fabric（profile json） |
| NeoForge | `maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge`（1.20.1 用 `.../forge`） | 下载 installer jar，执行 install_profile processors（同新版 Forge） |
| Forge（1.13+） | BMCLAPI `/forge/minecraft/<mc>` 或 `files.minecraftforge.net` 的 promotions + maven metadata | installer jar：解析 `install_profile.json` 与 `version.json`；下载 `libraries`；按 `data` 表替换变量后依次运行 `processors` |
| Forge（1.5–1.12） | 同上 | 旧版 installer：取 `install_profile.json.versionInfo` 加 universal jar 写入；更老的版本直接把补丁并入 jar（列为低优先级） |
| OptiFine | BMCLAPI `/optifine/<mc>`（官方无 API） | 下载 jar，用其自带 `optifine.Patcher` / `LaunchWrapper` 方式生成库；与 Forge 共存时作为 mod 放入 mods 目录 |

Forge / NeoForge processors 要点（对照 HMCL `ForgeNewInstallTask`）：

- `data` 中的值：`[maven:coord]` 解析为 libraries 路径；`'literal'` 去引号；`/path` 表示从 installer jar 里解压出来的文件。
- 内置变量：`{SIDE}=client`、`{MINECRAFT_JAR}`、`{MINECRAFT_VERSION}`、`{ROOT}`、`{INSTALLER}`、`{LIBRARY_DIR}`。
- 只执行 `sides` 为空或包含 `client` 的 processor。
- 每个 processor 是一个 jar：读它 manifest 的 `Main-Class`，用 `java -cp <jar + classpath> Main-Class args` 运行。这一步需要一个可用的 Java，安装器要先确保 Java 已就绪。
- `DOWNLOAD_MOJMAPS` 任务直接由启动器自己下载 `client_mappings`（走镜像），不让 processor 联网。
- 运行前把原版 jar 复制到临时目录再交给 processor，避免改坏共享 jar（HMCL 的做法）。
- `outputs` 中的哈希逐个校验；校验通过的可以跳过重复执行。

## 8. Java 运行时（fg-java）

### 8.1 发现

扫描器列表（参考 PCL.Core `Minecraft/Java/Scanner`，Apache-2.0）：

- `PATH`、`JAVA_HOME`
- Windows：注册表 `HKLM\SOFTWARE\JavaSoft`、`Eclipse Adoptium`、`Azul Systems`、`Microsoft`；常见目录 `Program Files\{Java,Eclipse Adoptium,Zulu,Microsoft}`；官方启动器自带运行时 `%LOCALAPPDATA%\Packages\Microsoft.4297127D64EC6_*\LocalCache\Local\runtime`
- macOS：`/usr/libexec/java_home -V`、`/Library/Java/JavaVirtualMachines`、Homebrew（`/opt/homebrew/opt/openjdk*`）
- Linux：`/usr/lib/jvm`、`/usr/lib64/jvm`、`/opt`、SDKMAN `~/.sdkman/candidates/java`
- 其它启动器目录：HMCL、PCL、Prism 的 runtime 目录（只读引用）
- 启动器自己的 `runtimes/`

解析：

- 优先读 `<home>/release` 文件（`JAVA_VERSION`、`IMPLEMENTOR`、`OS_ARCH`），不用启动 JVM。
- 没有 release 文件时才执行 `java -XshowSettings:properties -version`，超时 5s。
- Windows 上用 PE 头判断 x86 / x64 / ARM64（同 PCL.Core `PeHeaderParser` 的思路）。macOS 读 Mach-O 头判断 arm64 / x86_64，避免 Apple 芯片误选 Rosetta 版 Java。

### 8.2 自动匹配规则

- 首选 version json 的 `javaVersion.majorVersion`。缺失时按版本推断：≤1.16.5 → 8，1.17 → 16，1.18–1.20.4 → 17，1.20.5+ → 21，以后的新版以 json 为准。
- Forge 1.16.5 及以下需要 Java 8；部分旧 Forge 在 Java 9+ 上会失败，规则表单独标出。
- 优先选同架构；Apple 芯片上 1.18 以下原版缺少 arm64 natives，需要为 LWJGL 2 / 3.2 替换 arm64 natives，或回退到 x86_64 Java（Rosetta）。这里给出提示，见 §12。
- 都没有时提示一键下载：
  1. Mojang 官方运行时清单 `piston-meta.mojang.com/v1/products/java-runtime/.../all.json`（按文件清单逐个下载，可走镜像）。
  2. Adoptium API（Temurin）、Azul API（Zulu，提供 Java 8 arm64）作为发行版选项，与设计稿「可安装」列表一致。

### 8.3 内存分配

- 默认「自动」：自研策略，按系统总内存、可用内存和实例模组数给出建议区间。设计稿提示「建议为该整合包分配 4–6 GB」。
- 上限不超过物理内存减 2 GB（Android 另算，见 §11）。32 位 Java 上限 1 GB。
- 预设 JVM 参数模板：G1GC（默认）、ZGC Generational（Java 21+）、Aikar flags。

## 9. 启动流程（fg-game::launch）

点击「开始游戏」后按顺序执行，每步都是任务图节点，主页卡片显示当前步骤：

1. 预检：账户可用（离线账户直接通过；Yggdrasil 账户先 `validate`，失败则 `refresh`，再失败就弹登录框）；Java 存在且版本匹配；内存设置合法；游戏目录可写。
2. 资源补全：按 §7.1 补齐缺失文件，可在设置里关闭。
3. 解压 natives 到 `shared/natives/<hash>/`：遵守 `extract.exclude`（排除 `META-INF/`）。1.19+ 的 LWJGL natives 不用解压，由 `-Djava.library.path` 加 `-Dorg.lwjgl.librarypath` 指向。
4. 构建 classpath：解析后的 libraries（去重时同一 `group:artifact` 取最后出现的版本，加载器库优先）+ 主 jar。分隔符 Windows 为 `;`，其它系统为 `:`。
5. 拼参数：
   - 新格式 `arguments.jvm` / `arguments.game`：字符串或 `{rules, value}`，`features` 包括 `is_demo_user`、`has_custom_resolution`、`has_quick_plays_support`、`is_quick_play_singleplayer|multiplayer|realms`。
   - 旧格式 `minecraftArguments`：按空格拆分；JVM 参数自己补上 `-Djava.library.path`、`-cp`。
   - 占位符：`${auth_player_name} ${version_name} ${game_directory} ${assets_root} ${assets_index_name} ${auth_uuid} ${auth_access_token} ${auth_session} ${user_type} ${version_type} ${user_properties} ${natives_directory} ${launcher_name} ${launcher_version} ${classpath} ${classpath_separator} ${library_directory} ${resolution_width} ${resolution_height} ${game_assets} ${clientid} ${auth_xuid} ${quickPlayPath} ${quickPlaySingleplayer} ${quickPlayMultiplayer}`
   - 启动器附加：`-Xmx/-Xms`、用户 JVM 参数、`-Dfile.encoding=UTF-8`、`-Dstdout.encoding=UTF-8`、`-Dstderr.encoding=UTF-8`（Windows 中文路径与日志乱码问题）、macOS `-XstartOnFirstThread`、`-Dlog4j2.formatMsgNoLookups=true`。
   - authlib-injector：`-javaagent:<authlib-injector.jar>=<api-root>` 加 `-Dauthlibinjector.yggdrasil.prefetched=<base64(api 元数据)>`。
   - 快速启动（设计稿「跳过主菜单直接进入游戏」）：1.20+ 用 Quick Play 参数，旧版只支持直连服务器，用 `--server/--port`。
6. 命令长度：Windows 命令行上限 32767 字符，超长时把 classpath 写入 `@argfile`（Java 9+），Java 8 用 `-cp` 指向一个只含 `Class-Path` manifest 的 jar（HMCL 有 `CommandTooLongException` 这一类处理）。
7. 进程启动：`tokio::process::Command`，`current_dir = 实例游戏目录`。
   - Linux：可选 `gamemoderun` 包装，可选 `__NV_PRIME_RENDER_OFFLOAD=1` 等独显变量。
   - Windows：`CREATE_NO_WINDOW`，使用 `javaw` 或 `java`；设置进程优先级。
   - 包装命令与环境变量都可以在实例设置里自定义（高级）。
8. 进程管理：stdout / stderr 逐行读取，识别 log4j XML 事件格式（`<log4j:Event>`）并转成结构化日志，批量推送 `game.log`。「启动时显示日志」时由各原生客户端打开独立日志窗口。
9. 窗口行为：检测到游戏窗口出现（日志中出现 `Setting user:`、`LWJGL Version`，或首帧事件），再按设置隐藏 / 最小化启动器。游戏退出后恢复窗口。
10. 退出处理：非 0 退出码或出现 `crash-reports/` 新文件时，运行崩溃分析（§10.4），弹出结果并提供「导出诊断包」（日志、崩溃报告、启动命令（令牌打码）、系统信息、模组列表）。
11. 统计：累加 `play_seconds`，更新 `last_played`。

## 10. 内容与实例管理

### 10.1 账户（fg-auth）

第一阶段：

- 离线：UUID = `UUID v3("OfflinePlayer:" + name)`（与原版服务端规则一致）。可选自定义 UUID、本地皮肤（只对单人生效，通过本地 Yggdrasil 模拟服务配合 authlib-injector 实现，见下）。
- Yggdrasil / authlib-injector（LittleSkin、Blessing Skin 及任何兼容服务器）：
  - API 地址发现（ALI）：GET 用户输入的 URL，读取响应头 `X-Authlib-Injector-API-Location`，跟随到真实 API 根地址。设计稿「已识别为 Blessing Skin 服务器」读的就是 API 根元数据里的 `meta.serverName`、`meta.implementationName`、`skinDomains`、`feature.non_email_login`。
  - 接口：`/authserver/authenticate`（`requestUser: true`，`clientToken` 用启动器安装级 UUID）、`/refresh`、`/validate`、`/invalidate`、`/signout`；多角色时 `availableProfiles` 让用户选（设计稿「已绑定 1 个角色」）。
  - 皮肤：`/sessionserver/session/minecraft/profile/<uuid>?unsigned=false` 取 textures；上传皮肤用 `PUT /api/user/profile/<uuid>/skin`（authlib-injector 规范）。
  - authlib-injector jar：从 BMCLAPI 镜像或官方 `authlib-injector.yushi.moe/artifact/latest.json` 下载并校验 sha256。
  - LittleSkin 内置为预设服务器 `https://littleskin.cn/api/yggdrasil`。
  - 深链接 / 拖放：支持 `authlib-injector:yggdrasil-server:<urlencoded>` 拖入（设计稿「把 Yggdrasil 链接直接拖到这里」）。
- 存储：`accounts.json` 只放元数据，`accessToken` / `clientToken` 存进系统凭据库（keyring，key = `fograin/<account-id>`）。
- Microsoft：放到 M11，见 §12。数据模型先预留 `AccountKind::Microsoft`。

### 10.2 资源平台（fg-content）

Modrinth（`api.modrinth.com/v2`，无需密钥，必须设置 `User-Agent: Fograin/<ver> (contact)`）：

- 搜索：`/search?query&facets=[["project_type:mod"],["versions:1.21.4"],["categories:fabric"]]&index=relevance&offset&limit`
- 版本：`/project/<id>/version?game_versions=[..]&loaders=[..]`
- 批量反查本地文件：`POST /version_files { hashes, algorithm: "sha1" }`，用于识别已装模组与检查更新（`/version_files/update`）。
- 依赖：`dependencies[].dependency_type = required|optional|incompatible|embedded`。

CurseForge（只通过 MCIM 镜像 `https://mod.mcimirror.top/curseforge/v1` 访问，接口路径与官方 v1 相同，不需要 API Key）：

- MCIM 是第三方社区服务，客户端要做可用性探测：不可用时 CurseForge 来源在下载页置灰并提示，Modrinth 不受影响。
- MCIM 的数据是缓存的，新发布的文件可能有延迟；搜索结果以镜像为准。
- 请求带 `User-Agent: Fograin/<ver>`，遵守镜像方的限流约定（具体限额需向 MCIM 确认）。

- 搜索 `/mods/search?gameId=432&classId=6&gameVersion&modLoaderType&searchFilter&sortField`
- 本地识别：`POST /fingerprints/432`，fingerprint = 去掉空白字节（0x09 0x0A 0x0D 0x20）后的 MurmurHash2，种子为 1。
- 文件下载：`downloadUrl` 为 null（作者禁止第三方分发）时只能提示用户手动下载，并打开浏览器到对应页面。
- API Key 风险见 §12。

依赖解析（参考 PCL.Core `ModDependencyResolver`，Apache-2.0）：

- 安装一个模组时，递归解析 required 依赖，按「同 MC 版本 + 同加载器 + 最新 release」挑选版本。
- 已安装的同 ID 模组视为已满足；版本冲突时列出来让用户选。
- 检测 incompatible，提前拦截。
- 结果以「待安装清单」展示后再执行（下载页「安装到：雾林生存」）。

中文名与搜索：

- Modrinth / CurseForge 本身没有中文名。可选接入 MC百科的公开数据，或社区维护的映射表（需要确认许可与可用性，见 §12）。第一阶段先支持中文搜索词转英文关键词的简单映射，名称以官方英文为准。

### 10.3 本地实例内容管理

- 模组解析（只读 jar 内的元数据，不解压整个文件）：
  - Fabric `fabric.mod.json`；Quilt `quilt.mod.json`
  - Forge 1.13+ / NeoForge `META-INF/mods.toml`、`META-INF/neoforge.mods.toml`（`${file.jarVersion}` 从 `MANIFEST.MF` 的 `Implementation-Version` 补全）
  - 旧 Forge `mcmod.info`；LiteLoader `litemod.json`
  - 图标：从元数据的 `icon` / `logoFile` 取，缓存到 `cache/icons/`
- 启用 / 禁用：重命名为 `*.jar.disabled`（与 HMCL、PCL、Prism 兼容）。
- 更新检查：sha1 批量走 Modrinth `version_files/update`，murmur2 走 CurseForge fingerprints；结果缓存 6 小时（设计稿「3 个可更新」）。
- 资源包：读 `pack.mcmeta` 的 `pack_format`（及 `supported_formats`）与 `pack.png`；按 MC 版本对照表判断是否兼容（设计稿「包格式 42 · 1.21.x · 已兼容」）。启用状态写入 `options.txt` 的 `resourcePacks:[...]`。
- 光影：`shaderpacks/` 下的 zip 或目录；当前选中的光影读 `config/iris.properties` 或 `optionsshaders.txt`。
- 世界：`saves/<name>/level.dat`（fastnbt 读 `LevelName`、`GameType`、`LastPlayed`、`Version.Name`、`SizeOnDisk`）；`icon.png`；运行时检测 `session.lock` 是否被占用；备份为 zip，支持定时与保留份数（Android 设计稿「每天 1 次 · 保留 5 份」）。
- 原理图：`schematics/` 下的 `.litematic`、`.schem`、`.nbt`，读取尺寸与作者元数据。
- 文件监听：`notify` crate 监听实例目录，外部改动后刷新列表（去抖 500ms）。

### 10.4 日志与崩溃分析（fg-diag）

- 数据源：最近一次游戏 stdout、`logs/latest.log`、`crash-reports/*.txt`、`hs_err_pid*.log`。
- 规则引擎：规则定义在 `fg-diag/rules/*.toml`，每条规则包括 `id`、`pattern`（正则）、`source`（log/crash/jvm）、`captures`（提取模组名等）、`message_key`、`fix`（可选的一键修复动作，如「切换 Java 17」「删除重复模组」）。
- 第一批规则按类别覆盖（类别清单参考 HMCL `CrashReportAnalyzer` 的公开规则，GPL 兼容；规则文本自行编写）：Java 版本过高或过低、内存不足、显卡驱动 / OpenGL、文件损坏或被修改、模组缺依赖 / 重复 / 冲突、Mixin 注入失败、OptiFine 与 Forge / Sodium 不兼容、加载器重复安装、mod 被解压、ID 超限、实体 / 方块 tick 崩溃（提取坐标）。
- 结果展示：把可能原因按置信度排序，并给出对应模组和操作按钮。

### 10.5 整合包

导入：

- Modrinth `.mrpack`：`modrinth.index.json`（`files[]` 带 `hashes`、`downloads`、`env`）加 `overrides/`、`client-overrides/`。
- CurseForge `.zip`：`manifest.json`（`minecraft.modLoaders`、`files[{projectID, fileID, required}]`）加 `overrides`。用 `/mods/files` 批量换下载地址，拿不到的列表让用户手动下载。
- MultiMC / Prism：`instance.cfg` + `mmc-pack.json`。
- HMCL 整合包：`modpack.json`（第二优先级）。
- 直接拖入 `.minecraft` 文件夹或 zip：识别其中的 versions 目录。

导出：Modrinth `.mrpack`（首选，可从 Modrinth 解析的模组只写 URL），以及 CurseForge 格式；可选包含哪些目录（config、resourcepacks、saves…）。

「更新整合包」：记录来源项目与版本，新版本出来时对比文件清单做增量更新，保留用户自己加的文件。

### 10.6 服务器

- 列表来源：启动器级 `servers.json` 加各实例 `servers.dat`（NBT），可选择双向同步。
- 状态 Ping：1.7+ 的 SLP 协议（握手 + status JSON + ping），老版本用 `0xFE 0x01` 旧协议（参考 PCL.Core `Link/McPing`，Apache-2.0）。支持 SRV 记录（`hickory-resolver`）。设计稿展示的 MOTD 颜色码、人数、延迟、图标都来自 status 响应。
- 局域网发现：监听 UDP 组播 `224.0.2.60:4445` 的 `[MOTD]..[/MOTD][AD]port[/AD]` 广播。
- 「使用实例」：给服务器绑定默认实例，加入时用 Quick Play 直连。

## 11. Android 计划（第二阶段）

### 11.1 可行性与关键问题

在 Android 上运行 Java 版 Minecraft，需要的不只是一个启动器：

1. JVM：Android 没有标准 JRE，需要内置为 Android（bionic / aarch64）交叉编译的 OpenJDK 8 / 17 / 21。可用来源：Pojav / Amethyst 社区维护的 `android-openjdk-build`（GPL-2.0 with Classpath Exception），需要确认二进制分发义务。
2. LWJGL：官方 LWJGL 没有 Android 版，需要用移植版 LWJGL 3（Pojav 系的 `lwjgl3` fork，含 GLFW 的 Android 实现），并在启动时替换 version json 中的 LWJGL 库。
3. 图形：Minecraft 需要桌面 OpenGL，Android 只有 GLES / Vulkan。需要转译层：GL4ES、Zink（Mesa，OpenGL→Vulkan）、MobileGlues、ANGLE 等，按 GPU 选择。
4. 输入：触控转键鼠事件，虚拟按键布局编辑器，游戏手柄映射，软键盘输入（聊天）。
5. 进程模型：游戏要在独立进程中跑（`android:process=":game"`），用 `SurfaceView` / `ANativeWindow` 交给转译层渲染，JVM 通过 JNI 在该进程中创建（`JNI_CreateJavaVM`）。
6. 内存：低端设备 4–6 GB RAM，可分配给游戏的大约是物理内存的一半，且受系统 LMK 管理。
7. 商店政策：Google Play 不允许下载并执行可执行代码（模组 jar 就属于这类），Pojav 系项目都选择 GitHub / 侧载分发。我们的 Android 版也建议只做 GitHub Release 与国内应用商店，见 §12。

以上大部分属于 Pojav / Amethyst 的现成工作，自己重写几乎不现实。建议路线：复用它们的原生层（JRE、LWJGL 移植、渲染器 so），启动器 UI 用 Kotlin Compose，逻辑复用 Rust 核心。许可上 Amethyst 为 LGPL-3.0（需确认具体组件），与 GPL-3.0 兼容。

### 11.2 Android 架构

```
Kotlin Compose App（主进程 :main）
 ├─ Jetpack Compose UI：Material 3（设计稿 15 个 Android 画面）
 ├─ Rust：fg-* crate 复用（下载、安装、模组、账户、配置），经 UniFFI/JNI 调用
 └─ Android 平台层（Kotlin）
     ├─ GameLauncherService：启动 :game 进程的 Activity，传入启动参数（Intent / 文件）
     ├─ StorageBridge：SAF 文件选择、导入导出整合包、分享
     ├─ KeystoreBridge：令牌加密存储（Android Keystore）
     └─ DeviceInfo：RAM、GPU（GLES/Vulkan 扩展）、ABI

GameActivity（进程 :game，原生层复用 Amethyst）
 ├─ 解包后的 JRE（首次运行时从 assets 解压到私有目录）
 ├─ LWJGL Android 移植 + 渲染器 so（gl4es / zink / mobileglues）
 ├─ 触控控制层（Kotlin 自绘 Overlay，布局 JSON 可编辑）
 └─ 退出码、日志通过文件 + Binder 回传给主进程
```

Rust 侧的差异点：

- `fg-game::launch` 抽象出 `Launcher` trait：桌面实现 = 起子进程；Android 实现 = 生成启动描述 JSON（classpath、JVM 参数、游戏参数、渲染器、环境变量），交给 Android 平台层。
- `fg-java`：Android 只用内置 JRE，不做扫描。版本映射 8 / 17 / 21 按需下载额外的 JRE 包（体积大，不全部打进 APK）。
- 参数改写：替换 LWJGL 库路径、移除桌面 natives、注入 `-Dorg.lwjgl.opengl.libname=<renderer>.so`、设置 `POJAV_RENDERER` 一类环境变量（以复用组件的约定为准）。
- 下载：设计稿「仅 Wi-Fi 下载」，用 `ConnectivityManager` 回调控制下载引擎暂停与恢复。
- 存储：默认私有目录；导入导出走 SAF；不申请 `MANAGE_EXTERNAL_STORAGE`。

### 11.3 Android 分步骤

| 步骤 | 内容 | 产出 / 验收 |
| --- | --- | --- |
| A0 | 评估：编译 Amethyst 跑通 1.21 原版；确定复用组件清单与许可；确定 minSdk 26、只出 arm64-v8a | 评估报告；许可清单 |
| A1 | Compose 外壳（底部导航、Large Top App Bar、FAB），通过 UniFFI 对接现有 fg-* crate | 能浏览实例、下载原版文件 |
| A2 | Android 平台层骨架：Kotlin ↔ Rust（UniFFI/JNI）调用、事件回传 | 双向调用 demo |
| A3 | 内置 JRE 17 / 21：打包为可选下载资源，首次运行解压、校验、`chmod` | `java -version` 在设备上跑通 |
| A4 | GameActivity：集成 LWJGL 移植与 gl4es / zink，启动原版 1.21.4 进入主菜单 | 主菜单可交互 |
| A5 | 触控层：默认布局、编辑器、手柄映射、软键盘输入 | 能玩生存 |
| A6 | 加载器：Fabric / Quilt 优先（纯 Java，兼容性最好）；Forge / NeoForge 安装器 processors 在设备上跑（耗时长，需要后台服务和前台通知） | Sodium 整合包可运行 |
| A7 | 渲染器选择与 GPU 适配表（Adreno / Mali / Xclipse），崩溃回退策略 | 设置页「渲染器」选项 |
| A8 | 账户（离线、外置）、整合包、世界备份、服务器；Keystore 存储 | 与桌面功能对齐（除 Java 管理页） |
| A9 | 性能与发热：内存默认值、后台降级、前台服务通知、电池优化豁免引导 | 30 分钟稳定游玩 |
| A10 | 分发：签名、GitHub Release、国内商店（如需要）、自写 APK 更新检查 | 1.0 Android 预览版 |

## 12. 风险与待决问题

1. 微软正版登录（已决定延后）：新建的 Azure 应用必须通过 Mojang 审核表单（`aka.ms/mce-reviewappid`）才能调用 `api.minecraftservices.com`，否则返回 403。建议现在就注册 Azure 应用并提交审核，M11 时再接入。流程：设备码 OAuth → XBL → XSTS → `login_with_xbox` → entitlements → profile。
2. CurseForge：已决定只走 MCIM 镜像，不申请也不内置 API Key。风险是依赖第三方服务的可用性与数据延迟；MCIM 停服时 CurseForge 搜索、安装、CurseForge 整合包导入、指纹识别都会不可用。需要时可再补自建代理方案。
3. PCL 许可：`Plain Craft Launcher 2/` 目录不能作为实现参考，只用 PCL.Core。团队成员需要知道这一点。
4. Apple 芯片旧版本：1.18 以下没有官方 arm64 natives，需要自带一套 LWJGL arm64 natives 做替换（HMCL、Prism 都有类似做法），否则只能用 x86_64 Java 走 Rosetta。
5. Liquid Glass 私有 API：仅在非 App Store 分发的 macOS 构建中启用，并做运行时探测，失败时回退 `NSVisualEffectView`。
6. 签名与公证：macOS 需要 Developer ID 证书和公证；Windows 建议 EV / OV 代码签名，否则 SmartScreen 会拦截；Linux 出 AppImage、deb、rpm，Flatpak 可后续再做。
7. Android 复用组件许可（JRE 构建、LWJGL 移植、渲染器）需逐项确认，A0 阶段输出清单。
8. 中文模组名数据源：MC百科数据的使用许可未确认，第一阶段不内置。
9. UI 框架：已改为**四个原生客户端**——macOS SwiftUI/AppKit、Windows C#/WinUI 3、Linux Rust/GTK4、Android Kotlin/Compose。原 React/Tauri 方案不再使用，54 个画面按平台原生实现；后台核心仍是一套 Rust crate。
10. 浅色主题：已决定支持「跟随系统 / 浅色 / 深色」（§4.1.1）。设计稿只有暗色，§4.1.1 的浅色令牌是初稿，需要在 pen 里补浅色画面后再定稿。

## 13. 页面与后端能力对照

| 设计稿画面 | 主要 FFI 操作 / 事件 | 依赖模块 |
| --- | --- | --- |
| 主页 | `instance_list(recent)`、`launch_start`、`download_queue`、`system_info`、`news_list` | fg-game、fg-net、fg-native |
| 实例库 | `instance_list(group_by=loader)`、`instance_create`、`instance_import` | fg-game、fg-content |
| 实例详情 · 模组管理 | `mods_list`、`mods_toggle`、`mods_check_updates`、`mods_update`、`mods_remove` | fg-content |
| 实例详情 · 资源包 / 光影 / 世界 / 原理图 | `resourcepacks_*`、`shaders_*`、`worlds_*`、`schematics_*` | fg-content、fg-world |
| 实例详情 · 游戏设置 | `instance_settings_get/set`、`java_list` | fg-core、fg-java |
| 实例详情 · 维护 | `modpack_update`、`open_dir`、`logs_list`、`crash_analyze`、`instance_export`、`backup_*` | fg-content、fg-diag |
| 服务器 | `servers_list`、`server_ping`、`lan_discover`（event）、`servers_sync` | fg-world |
| 下载 | `content_search`、`content_versions`、`content_install_plan`、`content_install`、`task_*` | fg-content、fg-net |
| Java 运行时 | `java_scan`、`java_install`、`java_test_run`、`java_remove`、`java_rules` | fg-java |
| 账户 / 添加账户 / 添加认证服务器 | `account_*`、`yggdrasil_discover`、`skin_upload` | fg-auth |
| 设置 | `settings_get/set`、`cache_clean`、`storage_usage`、`updater_check` | fg-core、updater |
| 关于 | `app_info` | — |

## 14. 里程碑与逐步拆解

每一步都应能单独合并、单独验证。「验收」是该步完成的判断标准。

### M0 工程骨架与四端工作区（约 1 周）

1. Cargo workspace：`crates/fg-*` 与 `bindings/fg-ffi`；`cargo fmt`、`clippy -D warnings`、`test` 可用。
2. 建立四个独立 App 工程：`apps/macos`（SwiftUI）、`apps/windows`（C#/WinUI 3）、`apps/linux`（Rust/GTK4，独立 Cargo workspace）、`apps/android`（Kotlin/Compose/Gradle）。
3. `fg-ffi` 暴露 ABI 版本和健康检查；Swift/Kotlin/C# 各写一个调用 demo（只验证链接，不接业务）。
4. 设计令牌 `design/tokens.json` 与生成脚本；四个 App 使用同一套颜色/字号/圆角。
5. CI：Rust fmt/clippy/test；macOS `swift build`；Windows `dotnet build`；Linux `cargo build`；Android Compose 编译（SDK 就绪后）。
6. 日志与崩溃：`fg-diag` 写数据目录；原生 UI 通过 FFI 订阅日志/崩溃事件；各平台写自己的崩溃报告入口。
7. 删除旧 Tauri/React 工程与依赖，仓库只保留原生 App + Rust 核心。

验收：四个 App 都能启动一个空壳窗口；`fg-cli` 能跑核心单元测试；`fg_abi_version()` 在四个客户端中可调用。

### M1 四端原生外壳（约 3 周）

1. macOS：SwiftUI `NavigationSplitView` + AppKit 窗口桥；原生红绿灯、Vibrancy、原生菜单/快捷键、`NSOpenPanel`。顶部操作放在原生 `NSToolbar` 中：原生搜索框、segmented control、菜单与图标按钮，不使用自绘玻璃胶囊。
2. Windows：C#/WinUI 3 `NavigationView`；原生 `AppWindowTitleBar`、Mica、Snap Layout、文件选择。
3. Linux：GTK4/libadwaita `Adw.HeaderBar`、侧栏与内容分栏；原生文件对话框、Secret Service。
4. Android：Compose `Scaffold` + `NavigationBar`、`TopAppBar`；主题和动态取色；后续接 `GameActivity` 骨架。
5. 共享主题：跟随系统 / 浅色 / 深色，Rust 读取配置，各客户端首帧设置原生外观（§4.1.1）。
6. 每端用静态假数据做主页 + 实例库两个页面；不要求像素一致，要求原生控件和窗口行为。

验收：四个 App 启动后都有正确的原生窗口边框/标题栏/主题；主页和实例库可导航；macOS 红绿灯、Windows Snap Layout、Linux headerbar、Android 返回手势均正常。

> M2–M7 主要修改 Rust 核心，不依赖 UI 框架；四个原生客户端可以在这些里程碑中并行/延后接入。

### M2 网络与下载引擎（约 2 周）

1. `fg-net::Client`：reqwest + rustls、UA、代理（HTTP / SOCKS5 / 系统代理）。
2. 镜像规则表与候选源生成，带单测（覆盖每条前缀规则）。
3. 下载引擎：并发信号量、每主机限流、重试退避、校验、断点续传、限速、分段下载。
4. 内容寻址缓存与 HTTP 条件缓存。
5. 源健康度测速与熔断。
6. `fg-task`：任务图、进度聚合、取消、暂停、持久化。
7. `fg-cli download <url>` 用于手测；用本地 mock 服务器（`wiremock`）做集成测试：断线、416、哈希错误、全部候选失败。

> 已实现（2026-11-04）：`crates/fg-net`、`crates/fg-task`，取舍见 [ADR 0002](adr/0002-download-engine.md)。`fg-cli assets <version>` 是第 7 步与验收的手测入口。

验收：并发下载 1.21.4 的全部 assets（约 4000 个文件）不报错；中途断网恢复后可以续传。

### M3 原版安装与启动（约 2 周）

1. 版本清单与 version json 模型；`inheritsFrom` 合并；rules 求值。单测样本覆盖 1.0、1.7.10、1.12.2、1.16.5、1.20.1、1.21.4、快照与 rd-132211。
2. 安装任务图：json → jar + libraries + assetIndex → assets；log4j 修复。
3. Java 扫描与解析（`release` 文件、PE / Mach-O 头），自动匹配规则。
4. 启动参数构建：新旧两种格式、占位符、natives 解压、classpath、argfile 回退。
5. 进程管理与日志流；日志窗口。
6. 离线账户。
7. `fg-cli launch <instance>` 打通。

验收：三平台用离线账户启动 1.8.9、1.12.2、1.16.5、1.20.1、1.21.4 原版进入主菜单。

### M4 实例管理与界面接线（约 2 周）

1. 实例 CRUD、`instance.toml`、全局与实例设置继承。
2. 新建实例向导（版本选择、加载器、内存），对应设计稿 Android「新建实例」的同等字段。
3. 主页、实例库、实例详情 · 游戏设置页接真实数据；四个客户端分别接线，macOS 先行，其余平台复用 FFI 契约。
4. 资源补全与启动前校验。
5. 下载页「下载队列」面板与侧栏角标、Dock / 任务栏进度。
6. 外部 `.minecraft` 目录导入。

验收：完全通过 UI 完成「新建 → 安装 → 启动 → 退出 → 游玩时长更新」。

### M5 加载器（约 2–3 周）

1. Fabric、Quilt（profile json 路线）。
2. NeoForge 与 Forge 1.13+：installer 解析、processors 执行、`DOWNLOAD_MOJMAPS` 自处理、outputs 校验。
3. Forge 1.7.10–1.12.2 旧安装器。
4. OptiFine（单独安装，以及与 Forge 共存）。
5. 回归矩阵：每个加载器选 3 个 MC 版本 × 三平台跑 CI 冒烟测试（无头环境只验证到 `Setting user` 日志行出现）。

验收：回归矩阵全部通过。

### M6 Java 运行时管理（约 1 周）

1. Mojang 运行时清单下载；Adoptium、Zulu API 接入。
2. Java 运行时页：已安装列表、可安装列表、测试运行、卸载、默认内存、JVM 参数预设。
3. 自动下载：启动时缺 Java 自动弹窗安装。

验收：在干净的系统上启动任意版本时，能自动装好对应 Java。

### M7 外置登录（约 1 周）

1. Yggdrasil 客户端与 ALI 发现；authlib-injector 下载与注入；prefetched 元数据。
2. 账户页、添加账户、添加认证服务器（含识别服务器信息、拖入链接）。
3. keyring 存储；令牌校验与刷新。
4. 皮肤：3D 预览、上传（Yggdrasil 服务器）、离线本地皮肤。

验收：LittleSkin 账户能进入启用了 authlib-injector 的服务器。

### M8 模组与资源（约 3 周）

1. 本地模组解析（4 种元数据）、启用 / 禁用、图标缓存、文件监听。
2. Modrinth 搜索 / 版本 / 安装；CurseForge 同上（经 MCIM 镜像，含可用性探测与降级提示）。
3. 依赖解析与安装计划；更新检查与批量更新。
4. 资源包、光影、世界（含备份）、原理图页面。
5. 下载页全部 6 种资源类型的 tab。

验收：从下载页给 Fabric 实例装上 Sodium + Iris + 依赖，并能检测到更新。

### M9 整合包、服务器、崩溃分析（约 2 周）

1. mrpack、CurseForge、MultiMC 导入；mrpack 导出；更新整合包。
2. 服务器页：SLP Ping、SRV、局域网发现、`servers.dat` 同步、Quick Play 加入。
3. 崩溃分析规则引擎与首批规则；诊断包导出。

验收：导入一个 200+ 模组的 Modrinth 整合包并成功启动；故意制造 5 类崩溃都能识别出来。

### M10 打磨与发布（约 2 周）

1. 设置页全部项目、i18n（简体中文、English；繁体中文可后续加）。
2. 自动更新器（签名密钥生成与保管）、签名与公证、安装包。
3. 性能：冷启动时间、空闲内存（关于页目标：冷启动 0.4s、空闲 62MB，以实测为准）。
4. 无障碍：键盘导航、焦点可见、对比度检查、屏幕阅读器标签（完整的 WCAG 验证需要人工辅助技术测试）。
5. 关于页致谢与开源许可列表（`cargo-about`、`license-checker` 生成）。

验收：发布 1.0 桌面版。

### M11 微软正版登录（审核通过后，约 1 周）

1. 设备码流程与浏览器回调流程；XBL / XSTS / Minecraft 登录；所有权与 profile。
2. 令牌刷新、多账户、皮肤与披风管理（Minecraft Services API）。

### M12+ 后续

- Android（§11，A0–A10，估算 3–4 个月）。
- 联机（EasyTier 一类 P2P 组网，参考 PCL.Core `Link/EasyTier` 的接口思路）。
- 更多加载器（LegacyFabric、Cleanroom、LiteLoader）。

## 15. 测试策略

- 单元测试：version json 合并、rules、参数构建、镜像替换、murmur2、NBT 解析、崩溃规则。样本放在 `crates/*/tests/fixtures/`。
- 集成测试：`wiremock` 模拟 Mojang、BMCLAPI、Modrinth、Yggdrasil；下载引擎的故障注入。
- 端到端：`fg-cli` 在 CI 三平台上安装并启动（Linux 用 `xvfb-run`），检查日志关键行。
- FFI：为 `fg-ffi` 维护一套跨语言契约测试；Swift/Kotlin/C# 各有最小调用测试，防止 ABI 漂移。
- 原生 UI：
  - macOS：Swift Testing/XCTest + Xcode UI test（关键导航）。
  - Windows：xUnit + WinUI UI test（Windows App SDK）。
  - Linux：GTK 单元测试 + 手工/at-spi 冒烟测试。
  - Android：Compose UI test + `GameActivity` 启动冒烟测试。
- 视觉对照：每个平台截原生窗口图，与 pen 的布局参考人工比对；每个里程碑一次。
