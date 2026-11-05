# 0001 — 四个原生 UI 客户端共用一套 Rust 核心

- 状态：已接受（M0）
- 日期：2026-10-31
- 相关：`docs/design.md` §3、§4

## 背景

早期方案使用 React + Tauri，以 WebView 承载 54 个画面。该方案与各平台的窗口材质、标题栏、菜单、文件选择、输入法和无障碍语义冲突，也限制了冷启动与内存目标（§10）。

## 决策

1. 业务逻辑全部放在 `crates/fg-*`，不依赖任何 UI 框架。
2. 四个客户端各自原生实现：macOS SwiftUI/AppKit、Windows C#/WinUI 3、Linux Rust/GTK4、Android Kotlin/Compose。
3. 客户端只通过 `bindings/fg-ffi`（UniFFI + 稳定 C ABI）访问核心；Linux 是同语言特例，可直接依赖 crate，但不得把 GTK 类型带入核心。
4. 四端共享设计令牌（`design/tokens.json`）、FFI 契约与测试夹具，不共享 UI 代码。
5. 删除旧 Tauri/React 工程与依赖。

## 结果

- 每端都能使用平台原生控件与窗口行为，代价是同一页面要写四遍。
- FFI 契约成为跨端升级的同步点：ABI 版本变化时四端必须同时升级。
- 该决策的收益在 M1 得到验证：四端空壳都能启动并读到 Rust 侧的设置快照。
