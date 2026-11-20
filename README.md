# 雾雨启动器 · Fograin Launcher

Minecraft Java 版启动器：一套 Rust 核心 + 一套 Compose Multiplatform UI（Android / Windows / macOS / Linux）。桌面外壳按平台适配，页面与交互只有一份实现。

设计文档见 [docs/design.md](docs/design.md)，架构决策见 [docs/adr/](docs/adr/README.md)，待办见 [docs/backlog.md](docs/backlog.md)。

## 当前进度

| 里程碑 | 内容 | 状态 |
| --- | --- | --- |
| M0 | 工程骨架、FFI 健康检查、CI | 完成 |
| M1 | 四端原生外壳（导航、主题、窗口行为） | 完成（历史） |
| M2 | 网络与下载引擎（`fg-net`、`fg-task`） | 完成 |
| M3 | 原版安装与启动（`fg-game`、`fg-java`、`fg-auth`、`fg-cli`） | 完成（核心与 CLI） |
| M4 | 实例管理、安装/启动 UI 接线、任务队列、外部导入 | 完成 |
| M5 | Fabric/Quilt/Forge/NeoForge/OptiFine 安装器与启动接线 | 完成（2026-11-14） |
| M6 | Java 运行时自动下载、安装/启动自动补全与管理页 | 完成（2026-11-15） |
| M7 | 离线与第三方 Yggdrasil / authlib-injector 登录、账户页与启动接线 | 完成（2026-11-15）；不含皮肤管理与正版登录 |
| M8 | 模组、资源包、光影、世界管理，Modrinth / CurseForge 内容下载页与更新检查 | 完成（2026-11-15） |
| M9 | 崩溃分析与诊断包；按世界的数据包；整合包导入/导出、在线浏览与桌面拖放导入 | 完成（2026-11-17）；服务器功能同日移除 |
| R0 | Compose Multiplatform 重构：技术验证 | 完成（2026-11-12） |
| R1 | 共享层：kotlinx.serialization、平台接口、`AppModel` 去 Android 化、commonTest | 完成（2026-11-13） |
| R2 | UI 迁移：四页 + 3 个 sheet、字符串与图片资源化、Material 3 差异 | 完成（2026-11-13） |
| R3 | Android 薄壳切换：manifest/res/jniLibs/Activity 迁入 `apps/compose/android` | 完成（2026-11-14） |
| R4 | 桌面外壳：窗口状态、菜单快捷键、Esc 返回、原生目录选择器、宽屏 NavigationRail | 完成（2026-11-14） |
| R5 | 打包与 CI：DMG/MSI/DEB、tag 触发的 release workflow、三 OS CI 矩阵 | 进行中 |
| R6 | 清理：删除旧 `apps/android` 与旧文档 | 完成（2026-11-14） |
| M10 | 设置界面、四语言与主题色；自动更新、签名、发布打磨 | 进行中（设置与 i18n 于 2026-11-19 完成） |

M0–M4 早期实现了四套原生 UI（SwiftUI / WinUI 3 / GTK4 / Compose）。四端 UI 合计约 3 万行，同一个页面要写四遍，维护成本过高。2026-11-12 起按 [ADR 0004](docs/adr/0004-compose-multiplatform-ui.md) 收敛为**一套 Compose Multiplatform UI**：Rust 核心、FFI 契约、数据目录与配置不变；Android 端与桌面端运行同一份 `commonMain` 页面，平台外壳各自打包。

R0 已跑通：新工程三模块、Android/desktop 共用 UI、JNI FFI、DMG（71 MB）、冷启动 1.61 s、空闲 RSS 196 MB。R1/R2 把业务模型、`AppModel`、主题、组件与四个页面全部迁入 `commonMain`，桌面与 Android 渲染同一棵 Compose 树；iOS 之外的平台外壳只保留 Activity/Window、原生库与系统外观。三个桌面原生客户端和旧 `apps/android` 均已删除（R6，2026-11-14）。

R3/R4/R6 于 2026-11-14 收尾：`AppModel` 拆成四个按功能的 controller（`LibraryActions` / `DownloadsActions` / `LaunchActions` / `InstanceSettingsActions`），桌面补上窗口状态持久化、菜单快捷键、Esc 返回和系统目录选择器，Android 用 SAF 选目录，≥840 dp 的窗口改用 `NavigationRail`，并补齐 `AppModel` 状态机测试与 Compose UI 测试。

M7（2026-11-15）加入离线账户与第三方 Yggdrasil / authlib-injector 登录、账户面板和启动接线。按范围调整，皮肤管理从计划中移除；微软正版登录保留在 M11。

M8（2026-11-15）加入实例内容管理与内容下载页：模组、资源包、光影的扫描/启用/删除/更新检查，世界的列表/备份/恢复/删除，以及 Modrinth 与 CurseForge（MCIM）的搜索、版本选择、必需前置解析和安装。原理图（`.litematic` / `.schem` / `.nbt`）已从 M8 与全部后续计划中删除，实例目录不再创建 `schematics/`；数据包与整合包留在 M9。实现细节与手测记录见 [docs/m8.md](docs/m8.md)。

M10（进行中，2026-11-19）重做设置：从导航栏进入同一窗口内的设置页（与普通页面共用正向/返回动画，不再弹出第二个窗口）；宽屏为可拖动分隔的「分类侧栏 + 内容区」，窄屏为分类列表 → 详情，并保留 General → Java 的返回栈。外观页支持简体中文 / 繁體中文 / English / 日本語（默认跟随系统）、明暗主题、系统自动取色与自定义主题色（OKLCH 生成色板）；通用页可配置内存、JVM 参数、分辨率、全屏、日志、自动补全、Java 策略与初始页面；下载页可配置源、并发、分片、限速、重试与单主机并发；网络页支持代理模式/地址与超时；存储页显示数据目录、磁盘空间并支持下次启动切换目录；关于页展示构建信息与诊断复制。`launcher.toml` 新增 `[appearance] language/accent` 与 `[general] initial_page`，RPC 新增 `settings.get` / `settings.set`。宽屏主界面与设置内容区改为仅左侧圆角、其余边缘贴合系统窗口的内容面板（Windows 10/7 等无窗口圆角的系统使用直边）。同时修复了内容 API 测试在能访问 Modrinth 官方站点的 CI 上返回真实搜索结果而失败的问题（测试镜像表现在禁用官方候选）。

M9（2026-11-17）加入崩溃分析页、数据包安装和整合包导入/导出。`fg-diag` 用可覆盖的 TOML 规则分析 `latest.log`、崩溃报告与 `hs_err`，并导出脱敏诊断包；数据包按世界管理并可从下载页安装；整合包支持 Modrinth `.mrpack`、CurseForge/HMCL zip 与 MultiMC/Prism 外部实例，导入后写入 `modpack.json`，并可导出 `.mrpack`。下载页的「整合包 / 数据包 / 资源包」分类现在都有真实内容：整合包安装到新实例，数据包安装到选中的世界，资源包/光影/模组沿用内容浏览器；桌面支持把 `.mrpack` / `.zip` / MultiMC 目录拖进窗口导入，主页三点菜单也有「导入整合包…」。服务器列表、状态 Ping、局域网与快速加入在当天从产品范围移除。整合包增量更新留在 M9.1。实现细节见 [docs/m9.md](docs/m9.md)。

## 目录

```text
crates/fg-core     模型、配置、错误、数据目录
crates/fg-net      HTTP 客户端、镜像规则、下载引擎、缓存、源健康度
crates/fg-task     任务图、进度聚合、取消/暂停、持久化
crates/fg-game     版本清单、inheritsFrom 合并、rules、安装任务图、启动命令与进程
crates/fg-java     Java 发现、release/PE/Mach-O 解析、版本匹配
crates/fg-loader   Fabric/Quilt/Forge/NeoForge/OptiFine 版本发现与安装
crates/fg-auth     离线账户、Yggdrasil / authlib-injector 登录与账户仓库
crates/fg-content  本地模组/资源包/光影、内容索引、Modrinth / CurseForge、整合包与安装计划
crates/fg-world    世界、level.dat、数据包、zip 备份与删除
crates/fg-diag     日志与崩溃报告收集、规则引擎、诊断包导出
crates/fg-cli      无 UI 的调试与 CI 入口
bindings/fg-ffi    C ABI / JSON 契约
bindings/fg-ffi-jni JVM JNI 桥（Android .so + 桌面 .dylib/.dll/.so）
apps/compose/      Compose Multiplatform 工程（shared + android + desktop）
design/            设计令牌与参考图
docs/              设计文档、ADR、backlog
```

## 构建与测试

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
```

新 UI 工程（R0 起）：

```bash
# 桌面端（当前 OS）
./scripts/build-jni-desktop.sh
./apps/compose/gradlew -p apps/compose :desktop:run

# Android
./scripts/build-android.sh
./apps/compose/gradlew -p apps/compose :android:assembleDebug

# 共享层单测（commonTest，在 desktop JVM 上执行）
./apps/compose/gradlew -p apps/compose :shared:desktopTest

# 桌面 UI 测试（含 840 dp 断点、两条导航形态、离屏渲染截图）
./apps/compose/gradlew -p apps/compose :desktop:test
```

三 OS 与 Android 由 CI 覆盖（macOS / Linux（Xvfb）/ Windows / Android SDK 37）；打 tag 触发 `.github/workflows/release.yml` 产出 DMG/MSI/DEB/APK，签名步骤见 [docs/release.md](docs/release.md)。

## 原版安装与启动手测（M3）

```bash
# 查看缓存的版本清单（--refresh 绕过缓存）
cargo run -p fg-cli -- versions --limit 10

# 发现本机 Java；没有 release 文件的 JDK 会跑一次 -XshowSettings
cargo run -p fg-cli -- java list

# 安装原版并创建同名实例；--dry-run 只打印计划
cargo run -p fg-cli -- install 1.21.4
cargo run -p fg-cli -- install 1.21.4 --id my-instance --dry-run

# 打印启动命令（不启动游戏；第三方账户仍会校验令牌并准备 injector）
cargo run -p fg-cli -- launch my-instance --username Steve --dry-run

# 使用选中账户启动；--username 可临时覆盖为离线名称。日志会按 log4j 事件流式输出
cargo run -p fg-cli -- launch my-instance
cargo run -p fg-cli -- launch my-instance --username Steve

# 查看实例与已选 Java
cargo run -p fg-cli -- instance list
cargo run -p fg-cli -- java match 1.21.4
```

公共选项：`--root $FOGRAIN_HOME`（数据目录）、`--source auto|official|mirror`、`--region cn|global`、`--rate-limit-kib`。

数据目录沿用设计 §4.1：共享的 `shared/versions`、`shared/libraries`、`shared/assets`，实例在 `instances/<id>/`；下载缓存在 `cache/`。实例的 `instance.toml` 已按 `[loader]` / `[override]` / `[meta]` 预留，M4 的下载/实例设置流程不需要迁移。

Java 版本按 `version_json.javaVersion.majorVersion` 选择；缺失时按 §5.5 推断。1.8.9 / 1.12.2 / 1.16.5 需要 Java 8，本机没有时可用 `--java <home>` 指定；M6 起也会按需下载官方运行时（见下）。Apple Silicon 上这些老版本只有 x86_64 natives，启动器会优先下载 x86_64 Java 8 并用 Rosetta 运行。

## 内容、世界与下载页手测（M8）

```bash
# 先在实例里放入内容，或从下载页安装
cargo run -p fg-cli -- content list my-instance
cargo run -p fg-cli -- content search mod sodium --instance my-instance
cargo run -p fg-cli -- content versions AANobbMI mod --instance my-instance
cargo run -p fg-cli -- content install AANobbMI mod --instance my-instance
cargo run -p fg-cli -- content update my-instance

# CurseForge 只走 MCIM；模组需要 Fabric/Quilt/Forge/NeoForge 实例
cargo run -p fg-cli -- content install 1728334 mod --instance my-instance --provider curseforge

# 启用/禁用与删除
cargo run -p fg-cli -- content toggle my-instance mod sodium-fabric-0.6.13+mc1.21.4.jar --disable
cargo run -p fg-cli -- content delete my-instance mod sodium-fabric-0.6.13+mc1.21.4.jar --yes

# 世界备份与恢复
cargo run -p fg-cli -- world list my-instance
cargo run -p fg-cli -- world backup my-instance "New World"
cargo run -p fg-cli -- world backups my-instance
cargo run -p fg-cli -- world restore my-instance 20261115-180000.zip
```

在实例库点击实例进入实例详情页，模组 / 资源包 / 光影 / 世界 / 数据包五个分页就在页面内管理；设置页的「模组管理 / 资源包管理 / 光影包管理 / 世界管理 / 数据包」会直接跳到对应分页。实例详情页头只有一行：实例名、搜索、开始游戏、收藏和三点菜单（实例设置、复制目录、删除）。下载页的「模组 / 整合包 / 资源包 / 光影 / 数据包」五个内容分类都是真实浏览器；原理图不在下载页和实例管理中。

## 诊断、数据包与整合包手测（M9）

```bash
# 崩溃分析：先跑过游戏或放入 logs/latest.log
cargo run -p fg-cli -- diag logs my-instance
cargo run -p fg-cli -- diag analyze my-instance
cargo run -p fg-cli -- diag export my-instance

# 数据包按世界管理
cargo run -p fg-cli -- datapack list my-instance "New World"
cargo run -p fg-cli -- datapack import my-instance "New World" ~/Downloads/terralith.zip
cargo run -p fg-cli -- datapack toggle my-instance "New World" terralith.zip --disable

# 整合包：检查 → 导入新实例（基础游戏需另装）/ 导入已有实例 → 导出
cargo run -p fg-cli -- modpack inspect ~/Downloads/calm.mrpack
cargo run -p fg-cli -- modpack import ~/Downloads/calm.mrpack
cargo run -p fg-cli -- modpack import ~/Downloads/calm.mrpack --instance my-instance --dry-run
cargo run -p fg-cli -- modpack export my-instance
```

下载页的「整合包」分类会搜索 Modrinth / CurseForge，选中版本后「创建实例并导入」；「数据包」分类先选实例与世界，再安装到存档。桌面可以把 `.mrpack`、`.zip` 或 MultiMC/Prism 实例目录直接拖进窗口，主页右上角三点菜单也有「导入整合包…」。实例设置 →「日志与诊断」显示规则命中、修复入口和日志原文，右上角导出诊断包；「更多功能 → 导出整合包」写出 `.mrpack` 到 `<data root>/exports/`。增量更新尚未实现（M9.1）。

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

## 实例管理（M4）

`fg-ffi` 暴露一个异步服务（`fg_service_current` + `fg_rpc_call` + `fg_events_next`），所有命令以 JSON 方法名分派（契约见 `bindings/README.md`）：

```bash
# CLI 与 UI 共用的实例管理面
cargo run -p fg-cli -- instance create my-instance 1.21.4 --name 雾林
cargo run -p fg-cli -- instance list
cargo run -p fg-cli -- instance delete my-instance

# 外部 .minecraft 目录导入（只登记，不迁移文件）
cargo run -p fg-cli -- instance import ~/path/to/.minecraft --name 外部存档
```

UI 侧：主页/实例库读 `instances.list` / `instances.summary`；「添加实例」进入下载页，先选游戏 / 整合包 / 模组，再在游戏页选版本与加载器（原版与 Fabric/Quilt/Forge/NeoForge/OptiFine 均可用，加载器版本由 `loaders.versions` 实时读取），下载完成自动打开该实例的详情页，内存/JVM/分辨率等全部在实例设置页调整；下载队列页与底栏角标消费 `fg_events_next` 里的 `task.progress/task.finished`，并可用 `tasks.pause/resume/cancel` 控制任务；实例设置页通过 `instances.settings.set` 写 `[override]`，留空即继承 `launcher.toml [game]`；开始游戏走 `launch.start`，会话事件（`game.line` / `game.exited`）在退出后自动累加 `play_seconds` 并刷新「上次游玩」。Android 端通过 `bindings/fg-ffi-jni` 加载同一个 `fg-ffi` 服务，数据根目录由 Kotlin 在首次 RPC 前通过 `fg_set_data_root` 设为应用私有外部目录；Android 的游戏 Java 运行时属于 M12（A0–A10），当前 `launch.start` 会如实返回 `error.java.none_installed`，Java 管理页显示“不支持自动下载”。

## 加载器安装（M5）

```bash
# 列出某个 Minecraft 版本可用的加载器版本
cargo run -p fg-cli -- loaders 1.21.4 --kind fabric
cargo run -p fg-cli -- loaders 1.20.1 --kind forge
cargo run -p fg-cli -- loaders 1.20.1 --kind neoforge
cargo run -p fg-cli -- loaders 1.20.1 --kind optifine

# 安装加载器；省略 --loader-version 时取最新稳定版
cargo run -p fg-cli -- install 1.21.4 --loader fabric --loader-version 0.16.9 --id fabric-test
cargo run -p fg-cli -- install 1.20.1 --loader forge --loader-version 47.4.29 --id forge-test
cargo run -p fg-cli -- install 1.20.1 --loader neoforge --loader-version 47.1.106 --id neoforge-test
cargo run -p fg-cli -- install 1.20.1 --loader optifine --loader-version HD_U_I6 --id of-test

# dry-run 会写出 cache/install/<version-id>.json，便于检查文件与 processor 计划
cargo run -p fg-cli -- install 1.20.1 --loader forge --loader-version 47.4.29 --dry-run
```

加载器写入 `shared/versions/<version-id>/`，实例的 `[loader] version_id` 指向它；启动时仍然只解析这个子版本，Vanilla jar 从 `base_id` 回退。Forge/NeoForge/OptiFine 安装需要 Java；M6 起缺少运行时会先下载官方 JRE（`[java] auto_download`），Fabric/Quilt 只下载 JSON 与库。实现细节、processor 变量和验收项见 [docs/m5.md](docs/m5.md)。

## Java 运行时（M6）

M6 已接入 Mojang 官方运行时目录，下载到数据根目录的 `runtimes/<vendor>-<component>-<version>-<arch>/`，
并在安装加载器、启动游戏缺 Java 时自动补全。设置 → Java 运行时可以查看系统/托管运行时、下载、
删除并切换自动下载；实例设置里的 Java 下拉框会把选中的 home 写入 `[override] java`。

```bash
# 查看可下载的官方运行时（macOS arm64 上 Java 8/16 会标记为 x86_64 兼容模式）
cargo run -p fg-cli -- java catalog

# 安装 / 删除；--dry-run 只打印文件清单，计划写到 cache/java/<runtime-id>.json
cargo run -p fg-cli -- java install 21
cargo run -p fg-cli -- java install 21 --dry-run
cargo run -p fg-cli -- java remove mojang-java-runtime-delta-21.0.7-arm64

# 列表会区分系统 JDK 与启动器托管运行时
cargo run -p fg-cli -- java list
cargo run -p fg-cli -- java match 1.21.4

# 关闭单次自动下载（也会被 [java] auto_download = false 关闭）
cargo run -p fg-cli -- --no-auto-java install 1.20.1 --loader forge --loader-version 47.4.29
```

`launcher.toml` 新增：

```toml
[java]
auto_download = true
```

自动补全只在“一个 Java 都没有”时触发；已有其他 major 时沿用 M3 的降级+警告，避免为了精确
版本静默下载大文件。实现细节、平台回退表与验收项见 [docs/m6.md](docs/m6.md)。

## 账户（M7）

M7 交付离线账户和第三方服务器登录（Yggdrasil / authlib-injector）。账户面板从主页右上角
头像进入：切换账户、添加离线账户、第三方登录、多角色选择和删除。**不含皮肤管理**
（皮肤/披风由第三方服务端和 authlib-injector 在游戏内处理），**不含微软正版登录**
（保留在 M11）。

```bash
# 首次运行会从 [game] username 迁移一个选中的离线账户
cargo run -p fg-cli -- account list

# 添加并切换离线账户
cargo run -p fg-cli -- account add-offline Steve

# 第三方登录（推荐用环境变量传密码，避免进入 shell history）
FOGRAIN_AUTH_PASSWORD='…' cargo run -p fg-cli -- account login \
  https://littleskin.cn/api/yggdrasil --username user@example.com

# 多角色账户先看角色列表，再用 --profile 选择
cargo run -p fg-cli -- account login https://littleskin.cn/api/yggdrasil \
  --username user@example.com --profile <profile-id>

# 查看服务器元数据、下载/检查 authlib-injector、切换账户、刷新令牌、删除账户
cargo run -p fg-cli -- account discover https://littleskin.cn/api/yggdrasil
cargo run -p fg-cli -- account injector
cargo run -p fg-cli -- account select <account-id>
cargo run -p fg-cli -- account refresh
cargo run -p fg-cli -- account remove <account-id>

# 启动时默认使用选中账户；--account 可临时指定，--username 仅作一次性离线覆盖
cargo run -p fg-cli -- launch my-instance
cargo run -p fg-cli -- launch my-instance --account <account-id>
cargo run -p fg-cli -- launch my-instance --username Steve --dry-run
```

数据目录新增：

```text
<root>/accounts.json        # 账户元数据；不含令牌和密码
<root>/credentials.json     # 第三方 accessToken / clientToken（Unix 0600）
<root>/cache/authlib-injector/authlib-injector-<version>.jar
```

第三方账户在启动前会 `validate` 令牌，失效时自动 `refresh`；刷新失败提示重新登录。
authlib-injector 从官方 `latest.json`（经镜像）下载并校验 SHA-256，首次启动后复用缓存。
实现细节、接口与验收见 [docs/m7.md](docs/m7.md)。

## 桌面快捷键

| 快捷键 | 动作 |
| --- | --- |
| `Cmd/Ctrl+N` | 新建实例（跳到下载页） |
| `Cmd/Ctrl+I` | 前往实例库（再点「导入 .minecraft 目录…」） |
| `Cmd/Ctrl+F` | 打开全局搜索 |
| `Cmd/Ctrl+Shift+T` | 切换明/暗主题 |
| `Cmd/Ctrl+1..4` | 主页 / 实例 / 下载 / 设置 |
| `Cmd/Ctrl+Q` | 退出 |
| `Esc` | 关闭当前 sheet/对话框，再返回主页 |

窗口大小、位置与最大化状态写入 `java.util.prefs`，下次启动恢复。

GPL-3.0-only。参考实现与致谢见 `docs/design.md` §11。
