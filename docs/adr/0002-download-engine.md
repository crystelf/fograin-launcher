# 0002 — 网络与下载引擎的实现取舍

- 状态：已接受（M2）
- 日期：2026-11-04
- 相关：`docs/design.md` §5.1、§6、§14 M2

## 背景

M2 需要把 `fg-net` 与 `fg-task` 从占位 crate 变成可用的下载子系统，并保持与 M3 安装流程、M4 界面接线、后续 FFI 的兼容。设计文档给出了总体要求（镜像、并发、重试、续传、缓存、熔断、任务图），但没有规定 Rust 侧的 API 形状。本 ADR 记录实现时的具体取舍。

## 决策

### 依赖方向

- `fg-net` **不依赖** `fg-task`。引擎只接受 `DownloadCtx { CancellationToken, Arc<dyn ProgressSink> }`，由任务层适配。这样下载引擎可以单独测试，`fg-cli` 也不必引入任务图。
- `fg-task` 只依赖 `fg-core` 与 tokio。节点是闭包 `FnOnce(TaskCx) -> Future`，而不是设计稿里的 `trait Task { async fn run(&mut self) }`。原因是每个下载节点需要 move 自己的 `DownloadRequest`；用 trait 对象 + `async fn` 要么引入 `async-trait`，要么把参数生命周期写进 trait，复杂度和收益不成比例。

### 镜像与候选源

- 规则表放在 `crates/fg-net/src/mirrors.toml`，编译进二进制；`{bmcl}` 占位符在解析时展开，因此可以用 `FOGRAIN_BMCLAPI` 或 `<root>/cache/mirrors.toml` 覆盖根地址与规则，实现 §6.1 的热更新。
- 表版本不匹配、规则非法（例如 `official = false` 却没有镜像）会被拒绝并回退到内置表，坏配置不会让下载失效。
- CurseForge 的两条规则 `official = false`，严格遵循 §12：不内置 API Key，只走 MCIM。
- 候选顺序：`Auto` 按区域（简体中文 locale 或中国大陆时区 → 镜像优先），`Official` / `Mirror` 显式覆盖；健康度做稳定排序，把熔断中的源排到最后，但不会丢弃候选。

### 重试与断点续传

- 每个候选默认 5 次，退避 `200ms × 2ⁿ`，上限 5s。
- 哈希/大小不匹配：删除坏文件，**换下一个候选**，不在同源重试（§6.2）。
- 416、ETag 变化、远端长度变化：删除 `.part` 后**重试同源**（`AttemptError::Restart` 可重试）。这比直接换源更符合"从头下载"的语义。
- 续传状态写在 `dest.part.meta`（`url`、`etag`、`last_modified`、`total`）。只有 URL 相同的候选才续传：镜像之间不保证字节一致，拼接不同源的部分文件会得到"每个字节都合法、整体却错误"的结果，末尾的哈希校验才会发现，浪费带宽。
- 流式写入中断（断网、读超时、取消）时也先 `flush` + `sync_all` 再返回，否则缓冲区里最后几十 KB 会丢，下一次无法从真实位置续传。这一条由集成测试 `resumes_after_a_dropped_connection` 固定。

### 分段下载

- 只在已知大小（`size_hint`）且超过阈值时启用；分 2–16 段（默认 4）。
- 每段独立校验 `206` + `Content-Range`，任何一段失败就清理全部分段并让外层重试。
- 服务器忽略 `Range`（返回 200）时返回 `RangeUnsupported`，外层无惩罚地退回单流，不消耗重试次数。
- 分段下载不做"段级续传"：段文件是临时产物，失败后整份重下。真正的续传走单流 `.part` 路径。

### 缓存

- 内容寻址：`cache/sha1/<aa>/<hash>`，命中后重新计算哈希再硬链接（不可用时拷贝）到目标；损坏条目会被删除。
- 写入顺序是"先硬链接到缓存，再把 `.part` 改名到目标"，因此缓存条目在改名后仍然有效。
- HTTP 条件缓存：`cache/http/<aa>/<hash>.json` + `.body`，缓存键是**官方 URL** 而不是实际请求的镜像 URL，切换镜像时仍能命中 304。
- 元数据响应上限 32 MiB，避免把误配的镜像返回的大文件塞进内存。

### 源健康度

- 3 次连续失败熔断 5 分钟，成功后清零；状态写入 `cache/source-health.json`。
- 成功事件最多每 5 秒落盘一次（4000 个资源文件时否则会产生 4000 次写盘），失败事件与显式 `save()` 立即落盘。
- `probe_sources()` 用 HEAD 测 RTT，供"自动"模式与设置页展示；熔断只影响排序，不会让用户永久无法访问某个源。

### 任务图

- DAG + 拓扑调度，同层并行（`max_parallel`），默认 `min(cpu, 64)`，节点失败默认 `FailFast`，可选 `ContinueOthers`。
- 进度聚合严格按 `Σ(weight × fraction) / Σweight`；速度用 1 秒滑动窗口。
- 暂停 = 取消令牌 + 保留 `.part`，图返回 `interrupted = Paused`；恢复由调用方根据 `PersistedGraph` 的 payload 重建图（M2 只提供存储与读回，真正的重建属于 M3 安装器）。
- `TaskManager` 以 200ms 节流广播 `task.progress` / `task.state`，对应 §3.1 的事件契约；FFI 接线在 M4。

## 结果

- 正面：`fg-net` 与 `fg-task` 可以独立测试；镜像规则有覆盖每条前缀规则的单元测试；下载引擎的 13 个集成测试覆盖成功、重试、哈希错误、候选全失败、304、分段、断网续传、416、ETag 变化与清理。
- 负面/已知限制：
  - 镜像切换会从头下载该文件（不做跨源部分文件合并）。
  - 分段下载无段级续传。
  - MCIM 不可用时 CurseForge 来源不可用（符合 §12 的既定风险）。
  - 暂停只在节点检查 `TaskCx::check()` / `is_cancelled()` 时生效；不检查取消令牌的第三方 future 会拖慢暂停。
