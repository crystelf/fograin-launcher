# M4 交付检查（Windows 客户端接线）

- 日期：2026-11-09
- 基线：`e879dc8`
- 范围：`apps/windows/Fograin.Windows`；Rust core 与 macOS/Android/Linux 的检查见各自文档。

## 结论

**代码接线完成，未在 Windows 上运行验证。** Windows 客户端已按同一 `fg-ffi`
JSON 契约接入实例管理、下载/安装、任务队列、游戏会话、设置继承与外部
`.minecraft` 导入，UI 与交互路径对照 macOS 实现；所有控件使用 WinUI 3 原生控件。

本次工作机为 macOS，WinUI 3 的 XAML 编译器依赖 Windows-only API，无法在本机
完成 `msbuild`/`dotnet build` 或启动应用，因此**不把 Windows 运行验收写为完成**。
Windows CI 将是第一次真实构建。

## 接线内容

| 检查项 | 实现 | 状态 |
| --- | --- | --- |
| 启动设置快照 | `fg_settings_snapshot` → `launcher.toml` 主题在首帧前应用；`fg_abi_version` 健康检查 | 已接线 |
| 实例列表 / 主页 | `instances.list`、`app.snapshot`、`gameConfig.get`；最近/收藏/全部、列表/网格、双击进入实例库 | 已接线 |
| 实例库 | 按加载器分组、排序、标题栏搜索、预览检查器；双击打开「游戏设置」 | 已接线 |
| 下载页 | 分类切换、版本通道、版本搜索、`versions.list`、`version.plan`、安装对话框 | 已接线 |
| 安装 | `install.start` → 新建实例 → 任务队列 → `task.finished` 后进入「游戏设置」 | 已接线 |
| 任务队列 | `task.progress` / `task.finished` 事件轮询（250 ms）、侧栏角标、任务栏进度、取消任务 | 已接线 |
| 游戏设置 | `instances.settings.get/set`；内存/JVM/分辨率/全屏写 `[override]`，空值继承 `[game]` | 已接线 |
| 启动 / 停止 | `launch.start`、`session.stop`、`game.started/windowReady/exited`，退出后刷新 `play_seconds` | 已接线 |
| 外部 `.minecraft` 导入 | 原生 `FolderPicker` → `instances.import`，只登记不移动文件 | 已接线 |
| 原生控件 | `TitleBar`、`NavigationView`、`SelectorBar`、`ListView`/`GridView`、`ContentDialog`、`InfoBar`、`MenuFlyout`、`FolderPicker`、`ITaskbarList3` | 已使用 |

主页的运行控制（开始/停止/游戏设置/打开目录）放在 Hero 行动区：WinUI
`TitleBar.Content` 居中，不承载 macOS 标题栏的胶囊控件；信息架构与操作路径一致。

## 本机验证

### 1. C# 编译检查

用 .NET 8 SDK 的 Roslyn 直接引用 Windows App SDK 1.7 与 Windows SDK 投影程序集编译
全部客户端 C#（XAML 生成成员用仅本机、未入库的桩文件补齐）：

```text
0 warnings / 0 errors
```

这一步覆盖类型、可空性、P/Invoke 签名、WinUI API 名称与事件签名。

### 2. XAML 静态检查

在本机对 XAML 做结构检查（不代替 XAML 编译器）：

```text
124 x:Bind 路径可解析
222 StaticResource / ThemeResource 引用有定义
57 个事件处理函数存在且签名匹配
37 个 Symbol 枚举值合法
66 个 Style Setter 属性存在于目标类型
元素属性与枚举属性值无未知项
```

### 3. FFI 契约实测

把客户端实际的 `CoreBridge.cs` / `CoreWire.cs` 编入一个 macOS 控制台程序，直接
P/Invoke 本仓库构建的 `libfg_ffi.dylib`，在临时 `FOGRAIN_HOME` 下执行 52 项检查，
全部通过：

- `fg_abi_version` / `fg_settings_snapshot`（数据目录、主题、配置错误）
- `instances.list/create/favorite/dir/import/delete`
- `instances.settings.get/set`：内存、JVM 参数、分辨率、全屏，以及 `null` 继承 `[game]`
- `app.snapshot`、`gameConfig.get/set`、`tasks.list`
- 本地构造的安装图：`install.start` → `task.progress` / `task.finished` → 实例
  `installed = true`
- 未知方法的错误映射、`fg_events_next` 空队列
- `versions.list` 与 `version.plan` 的真实响应解析

> 首次运行因 `FOGRAIN_HOME` 未带上而写入本机真实数据目录的 `[game]` 段；已恢复为
> 默认值（`username = "Steve"`、`memory_mb = 0`、`show_log = false`，主题保留
> `light`），后续检查全部使用临时目录。

## 未验证 / 已知限制

- 未运行 Windows `msbuild`、WinUI XAML 编译器与应用本体；页面布局、对话框尺寸、
  主题资源查找、焦点与键盘行为需在 Windows 上复验。
- 任务栏进度使用 `ITaskbarList3`，COM 可用性已在代码中兜底，未在 Windows 运行。
- M5 加载器安装器、M6 Java 自动下载仍未实现；安装对话框只有原版可安装。
- 主题切换 UI 属于 M10；启动时主题由 `launcher.toml` 决定。

## 提交范围

本次交付单独提交，只包含 Windows 客户端、`apps/windows/README.md` 与本文档；
工作树中其他端（Android / Linux / core）的并行改动不在该 commit 内。
