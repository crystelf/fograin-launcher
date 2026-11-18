# 待办与优化

按优先级排列；`P0` 是重构内必须完成，`P1` 是重构后第一个版本，`P2` 是后续。

## 桌面布局

- ✅ **P1 — 桌面自适应布局**：≥840 dp 用 `NavigationRail`，<840 dp 保持底栏；同一套目的地与页面。验收：1280×860 与 960×640 两档离屏截图（`AdaptiveLayoutRenderTest`）。注：两档都 ≥840 dp，因此都走 rail；720×900 作为底栏对照。

## 桌面外壳（v2）

- ✅ P1 — 原生目录选择器：macOS `choose folder`、Windows `FolderBrowserDialog`、Linux `zenity`/`kdialog`；Swing 仅作回退（2026-11-15）。
- ✅ P1 — Debug 运行时的 Dock/任务栏图标与 macOS 标题栏跟随系统明暗（2026-11-15）。
- ✅ P1 — 全局搜索：导轨搜索入口 + 实例/页面/操作结果，`Cmd/Ctrl+F`（2026-11-15）。
- ✅ P1 — macOS 全窗内容 + 透明标题栏，原生红绿灯叠在导轨左上角（2026-11-15）。
- P2 — macOS：Dock 徽标与任务进度（`NSDockTile`）。
- P2 — Windows：Mica/Acrylic 背景、自定义标题栏、任务栏进度（`ITaskbarList3`）。
- P2 — Linux：Unity LauncherEntry / DBus 进度（可用时）。
- P2 — 托盘与「关闭到托盘」；单实例锁（Windows mutex / Unix socket）。
- P2 — 系统通知（`UNUserNotificationCenter` / AppNotification / GNotification）。
- P2 — 文件拖放导入（`.mrpack`、`.zip`、`.minecraft` 目录）。

## 账户（M7 后续）

- P1 — 第三方令牌迁入系统凭据库：macOS Keychain、Windows Credential Manager、
  Linux Secret Service、Android Keystore；当前是 `credentials.json`（Unix 0600）。
- P1 — LittleSkin / 自建 authlib-injector 服务端的真实回归：多角色、令牌过期后自动刷新、
  进入 online-mode 服务器与游戏内第三方皮肤显示；CI 只用 wiremock。
- P2 — 账户导入/导出（不含令牌）与多设备同步。
- P2 — 微软正版登录（M11，需 Azure 应用审核）。
- P2 — 第三方服务端预设列表（LittleSkin 之外的常用 ALI 服务）。
- P2 — authlib-injector 版本检查与自动更新：当前缓存优先，不主动查询新版本。

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
- ✅ P1 — 加载器安装前的 Java 版本校验与安装指引：M6 已接入 Mojang 官方运行时下载；`install.start` / `launch.start` 缺 Java 时排队下载后继续。
- P1 — Java 运行时第二来源（Adoptium Temurin）：覆盖 Linux arm64 等 Mojang 没有官方包的平台；需要 tar.gz 解包与 GitHub 镜像策略。
- P2 — Java 下载的暂停/取消已接入下载队列；Java 管理页仍只显示进度，后续补内联控制与半成品清理。
- P2 — 托管运行时自动更新（LTS 安全更新）与按 major 固定版本；当前只在新安装时取目录里的最新版。
- P2 — 运行时删除后的引用清理：实例 `[override] java` 指向已删除目录时提示改选，而不是启动时报错。
- ✅ P2 — Fabric API 从“加载器”迁到 M8 的模组内容，支持一键安装前置（2026-11-15：Modrinth/CurseForge 必需依赖自动解析）。
- P2 — 旧版 Forge（1.5 及更早）installer 兼容；当前支持 1.7–1.12 的 `versionInfo` / `version.json`。
- P2 — 加载器版本列表本地缓存与手动刷新；当前每次打开安装页读取元数据（HTTP 条件缓存仍在 `fg-net`）。

## 内容与世界（M8 后续）

- ✅ P1 — 实例管理改为页面：实例库点击实例进入详情页，模组 / 资源包 / 光影 / 世界在页内管理，
  实例设置也改为同级页面；页面与分类切换复用下载页动效（2026-11-16）。
- ✅ P1 — UI 精简：移除主页「运行环境 / 版本动态」大卡片、所有「已就绪」标签、实例详情的
  概览大卡片与占用空间/内容数/上次游玩指标，以及模组加载器与下载页提示文案（2026-11-17）。
- P1 — 内容图标与封面：网络项目图标缓存到 `<root>/cache/content-icons/`，本地 jar 内图标只读提取；
  需要一套 `commonMain` 可用的图片解码（expect/actual）再接入列表与详情。
- P1 — CurseForge 未索引文件的指纹更新检查：实现 Murmur2（跳过空白与 jar 头字段）后接
  `/v1/fingerprints`；当前只对已索引文件查询项目最新版本，未索引文件计为 unknown。
- P1 — 内容更新检查缓存与并发：项目多时串行请求较慢；计划缓存 6 小时并按来源并发 4–8 路。
- P2 — 内容下载页分页/排序/分类筛选：当前只取前 50 个项目，按最热门排序。
- P2 — 从本地文件安装 `.jar` / `.zip`（文件选择器或拖放，见桌面外壳的拖放项）。
- P2 — 内容依赖冲突与版本约束求解：当前每个必需前置取最新兼容版本，不校验互斥/重复。
- ✅ P2 — 数据包管理（按世界）与整合包导入/导出：M9 交付 Modrinth `.mrpack`、CurseForge/HMCL zip 与
  MultiMC/Prism 外部实例（2026-11-17）。
- ✅ P2 — 世界 `level.dat` 解析：M9 起显示名称、游戏模式、版本、种子与最后游玩时间（2026-11-17）。
- P2 — 世界恢复的覆盖/改名策略与备份保留策略（按数量/大小自动清理）。
- P2 — 世界备份进度与取消：大世界压缩目前是阻塞式 RPC，UI 没有进度条。

## 内容浏览、诊断与整合包（M9 后续）

- ✅ P1 — 崩溃分析与诊断包：`fg-diag` 规则引擎（内嵌 TOML + 用户覆盖）、置信度排序、修复入口与脱敏 zip
  （2026-11-17）。
- ✅ P1 — 下载页整合包/数据包分类：Modrinth / CurseForge 搜索、版本选择，整合包安装到新实例，
  数据包安装到选中世界（2026-11-17）。
- ✅ P1 — 拖放导入：桌面窗口接受 `.mrpack` / `.zip` / MultiMC 目录并显示全窗提示（2026-11-17）。
- P1 — 整合包增量更新与失败回滚：导入中途失败会留下部分实例与文件；需要按 `modpack.json` 清理
  或提供「修复导入」。
- P1 — 整合包搜索分页与排序：当前只取前 50 个项目，按下载量排序。
- P2 — CurseForge 整合包的下载地址解析：当前每个文件一次 `download-url` 请求；项目多时可并发解析并缓存。
- P2 — CurseForge 整合包的文件分类：manifest 只有 projectID/fileID，当前统一放进 `mods/`；
  需要读取项目 `classId` 才能把资源包/光影放进正确目录。
- P2 — 拖放导入的 Android 支持：文件管理器拖放/分享 `.mrpack` 到启动器；当前只支持桌面。
- P2 — 数据包更新检查：安装后的数据包不写内容索引，目前无法检查在线更新。
- P2 — 诊断规则的图形化编辑与问题反馈入口；当前只能编辑 `<data root>/diag/rules/*.toml`。

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
