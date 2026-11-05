# 0003 — 原版安装与启动的实现取舍

- 状态：已接受（M3）
- 日期：2026-11-05
- 相关：`docs/design.md` §5.1、§7.1、§8、§9、§14 M3、ADR 0002

## 背景

M3 把 `fg-game`、`fg-java`、`fg-auth` 从占位 crate 变成可用的原版安装与启动链路，并在 `fg-cli` 上打通「列版本 → 安装 → 启动」。设计文档描述了合并、rules、natives、参数与进程管理的目标，但没有规定 Rust 侧的 API 形状。本 ADR 记录实现时的取舍，以及明确留到后续里程碑的部分。

## 决策

### 依赖方向

- `fg-game` 依赖 `fg-net`（下载）、`fg-task`（安装图）、`fg-java`（匹配）、`fg-auth`（离线账户）和 `fg-core`。
- `fg-java` 不依赖 `fg-game`；`required_major(version_id, declared)` 由调用方传入 version json 的 `javaVersion.majorVersion`。这样 Java 发现可以单测，`fg-loader`（M5）也能复用。
- `fg-auth` 的 M3 范围只有离线账户；`AccountKind` 预留 `Yggdrasil` / `Microsoft`，M7/M11 不需要改发布面。

### 版本模型与合并

- `VersionJson` 保留未知字段（`#[serde(flatten)] extra`），避免加载器 profile 或未来 Mojang 字段在合并时丢失。合并时 `extra` 父后子覆盖。
- `inheritsFrom` 链由 `resolve_chain(&[leaf, parent…])` 校验：每层的 `inheritsFrom` 必须恰好指向下一层，缺父或环（超过 8 层）都报 `error.version.inherits_*`。
- 标量字段子覆盖父；`libraries` **子在前、父在后**；`arguments` 父在前、子在后拼接（`game`/`jvm`/`default-user-jvm` 分别拼接）；`minecraftArguments` 子有则用子，否则继承父。
- classpath 去重按 `group:artifact[:classifier]`，**保留第一次出现**。因此加载器/子版本的库优先；现代 natives 带 classifier，不会和同坐标的 base jar 互相吞掉。设计稿写的是「取最后出现的版本」，这里按「加载器库优先」的目标实现，差异记录在结果一节。

### rules 与 natives

- 空 rules = 允许；否则先默认拒绝，按顺序应用匹配的规则，**最后匹配者胜**；`os` 与 `features` 同时存在时必须全部满足。
- `os.version` 用正则；2026 快照新增的 `os.versionRange` 用数值比较，`min` 含、`max` 不含。
- `arch` 严格匹配：`x86` 不会匹配 `x86_64`。
- 两代 natives 都支持：
  - 1.18 及以前：`natives` 映射 + `downloads.classifiers`，`${arch}` 展开为 32/64，启动时解压到 `shared/natives/<instance-hash>/`，遵守 `extract.exclude`（默认 `META-INF/`）。
  - 1.19+：`name` 带 `:natives-<os>[-<arch>]` 的普通库。同一 `group:artifact` 同时存在 `natives-macos` 和 `natives-macos-arm64` 时，arm64 只取 `-arm64`，避免两个同名 dylib 上 classpath；`natives-macos-patch` 这类非架构通用包仍然保留。Modern natives 在 classpath 上，不解压，`-Djava.library.path` 指向空的 natives 目录。
- 解压用 `zip` crate 的 `enclosed_name()`，拒绝 `..` 路径穿越；natives 目录写 `.fograin-natives` 摘要标记，版本/分类器变化时整目录重建。

### 安装计划与任务图

- 计划（`InstallPlan`）与执行分离：`Installer::plan` 解析版本、拉 asset index、列出全部文件（client / libraries / assets / assetIndex / logConfig）和总字节数；`Installer::graph` 把五类文件变成任务图节点，`finalize` 依赖全部下载节点。
- 计划可序列化到 `<root>/cache/install/<version>.json`。恢复时重建同一张图，靠下载引擎「目标已存在且哈希正确」的检查跳过已完成文件；`fg-task` 的 `PersistedGraph` 仍只用于 UI 的任务列表，不把闭包节点反序列化。这样避免把下载参数和闭包状态写进持久化格式。
- 每个节点一个聚合 `ProgressSink`，直接调用 `TaskCx::advance`；权重按 MiB 计算并 clamp 到 1..10000，避免 400 MiB 的 assets 节点把其余节点压成 0。
- asset index 在计划阶段就下载并写入 `shared/assets/indexes/`；因此「1 already present」在第一次 dry-run 时就会出现，计划本身不会重复下载。
- `virtual` / `map_to_resources` 两套老布局：`finalize` 为 virtual 建 `shared/assets/virtual/<index>/`，`map_to_resources` 在 plan 带 instance id 时复制到 `.minecraft/resources/`；启动时再兜底检查一次。
- 1.7–1.18.1 使用内置 `fograin-log4j2.xml`（`%msg{nolookups}`），并把 `-Dlog4j2.formatMsgNoLookups=true` 加进 JVM 参数；1.19+ 使用 Mojang 下发的 `logging.client.file`。固定配置只写 `shared/assets/log_configs/`，不覆盖 Mojang 文件。

### Java 发现与匹配

- 扫描顺序：`JAVA_HOME`、`PATH`、平台标准目录、Homebrew / SDKMAN / Windows 注册表与官方启动器 runtime、`<root>/runtimes`。结果按 canonical path 去重。
- 优先读 `release` 文件的 `JAVA_VERSION` / `OS_ARCH` / `IMPLEMENTOR`，不启动 JVM。没有 `release` 时才跑 `java -XshowSettings:properties -version`，5s 超时且 `kill_on_drop`。
- 架构来自 PE / Mach-O / ELF 头，避免 Apple 芯片把 Rosetta 版 Java 误当 arm64。
- `required_major` 优先用 `javaVersion.majorVersion`；缺失时按 §8.2 规则推断（≤1.16.5→8、1.17→16、1.18–1.20.4→17、1.20.5+→21），快照按年份粗分。
- 选择规则：精确 major 优先，其次最小的更高 major，最后报错；同 major 优先 host arch。选不到精确版本时返回 `TooNew` warning 而不是失败，方便用户在 M6 自动装 Java 之前先看到提示。
- M3 不做 Java 下载；`<root>/runtimes` 已经被扫描，M6 可以直接往那里放。

### 启动命令

- `LaunchPlan::build` 是纯的（除 natives 解压与老资源物化），不做网络访问，`--dry-run` 可以在 CI 里跑。
- 占位符统一替换，未知占位符替换为空串，避免把 `${...}` 字面量传给游戏。`auth_uuid` 用无横线形式；离线 `accessToken` 为 `0`，`userType=legacy`。
- `arguments.default-user-jvm`（2026 快照）作为默认 JVM 参数，放在用户 JVM 参数之前；instance 的 `[override].jvm_args` 在其后。
- 命令超长时：Java 9+ 写 `@argfile`；Java 8 写 manifest-only 的 pathing jar（`Class-Path` 按 72 字节换行），把 classpath 从命令行移走。Java 8 不支持 `@argfile`，这一点不能只靠 argfile 兜底。
- 日志：stdout/stderr 逐行读，识别 `<log4j:Event>` 多行块并解析为 `Log4jEvent { logger, timestamp, level, thread, message }`；同时写入实例 `logs/fograin-<timestamp>.log` 并广播 `GameEvent`。`Setting user:` / `LWJGL Version:` / `Backend library: LWJGL` 触发一次 `WindowReady`。
- Apple Silicon 上 1.18.2 及更早只有 x86_64 的 LWJGL natives。`LaunchPlan::build` 在这种情况下拒绝用 arm64 Java 启动（`error.launch.arm64_natives`），CLI 的 Java 选择优先挑 x86_64 runtime（Rosetta）；没有 x86_64 Java 时给出明确提示，而不是让游戏在 `UnsatisfiedLinkError` 里崩溃。1.19+ 的 `natives-macos-arm64` 不受影响。
- 退出后 CLI 更新 `instance.toml` 的 `last_played` 与 `play_seconds`；非 0 退出/信号返回错误，M9 再接管崩溃报告分析。

### CLI

- `fg-cli install <version>` 默认创建同名实例；`--id` 指定实例 id，`--no-instance` 只装共享文件，`--dry-run` 只打印计划，`--refresh` 绕过缓存，`--strict` 安装后全量校验 SHA-1。
- `fg-cli launch <instance>` 只用本地 version json，不联网；`--username` 默认 `Steve`，`--java` 覆盖发现结果，`--dry-run` 打印命令，`--json` 输出结构化计划。
- `fg-cli versions` / `java list` / `java match` / `instance list|show` 用于验收和排障。

## 结果

- 正面：
  - 真实安装 1.21.4：4112 个文件、496.9 MiB，约 343s（本机、CN 镜像自动排序），无失败；内容缓存同时落地。
  - 真实启动 1.21.4（离线账户、Temurin 21.0.9 arm64）进入主菜单：日志出现 `Setting user: Steve`、`Backend library: LWJGL version 3.3.3+5`、`Reloading ResourceManager: vanilla`；CLI 收到 `WindowReady`，退出后写入 `play_seconds`。
  - 真实安装并启动 1.8.9、1.12.2、1.16.5（Zulu 8 x86_64 / Rosetta）与 1.20.1（Temurin 21，版本声明 Java 17）：natives 解压、log4j 修复配置、离线 session、`Setting user`/`LWJGL Version` 均正常，退出后写入 `play_seconds`。macOS 上 M3 验收的五个版本都已进入主菜单。
  - `wiremock` 集成测试覆盖 manifest → version json → client/libraries/assets/log → launch 计划全链路；9 个真实 version json fixture 覆盖 1.0、1.7.10、1.8.9、1.12.2、1.16.5、1.20.1、1.21.4、26.4-snapshot-7、rd-132211 的合并、rules、natives 与参数。
- 负面/已知限制：
  - 1.8.9 / 1.12.2 / 1.16.5 需要 Java 8；M3 不自动下载 Java，验收时用 `--java` 指向 Java 8（M6 补自动安装）。Apple Silicon 上这些版本还需要 x86_64 Java 8（Rosetta），arm64 Java 会在 preflight 被拒绝。
  - 本次环境只验证了 macOS；Windows / Linux 的真实 GUI 启动留到 CI 的 `xvfb-run` / 用户手测（设计 §15）。
  - 加载器（M5）尚未实现；`instance.toml` 的 `[loader]` 已按设计形状预留，`launch` 对非 vanilla 直接报 `error.launch.loader_unsupported`。
  - 资源补全（启动前按需下载缺失文件）属于 M4；M3 的 launch 只校验 client / libraries / log config 是否存在，不校验 4000 个 asset 对象。
  - Java 8 的 32 位 1 GB 上限属于 M6/§12；macOS 1.18 以下目前只做「x86_64 Java / Rosetta」回退与明确报错，不替换 LWJGL natives。
  - classpath 去重保留第一次出现（子/加载器优先），与设计稿「取最后出现」的文字相反；这是为了满足同一句里的「加载器库优先」目标。
  - `--json` 目前是 CLI 的调试输出，不是 FFI 契约；M4 再接 UI 时以 `fg-ffi` 的类型为准。
