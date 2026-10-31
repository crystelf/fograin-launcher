# 雾雨启动器 · Fograin Launcher

Minecraft Java 版启动器。设计文档见 [docs/design.md](docs/design.md)，当前架构决策见
[docs/adr/0001-native-ui-clients.md](docs/adr/0001-native-ui-clients.md)。

## 架构

Rust 核心 + 四个独立原生 UI 客户端：

```text
crates/                 # 共享 Rust 业务核心（fg-net、fg-game、fg-java、…）
bindings/fg-ffi/        # 版本化 C ABI / UniFFI 边界
apps/macos/             # SwiftUI + AppKit 桥
apps/windows/           # C# + WinUI 3
apps/linux/             # Rust + GTK4 + libadwaita
apps/android/           # Kotlin + Jetpack Compose + GameActivity
design/                 # tokens.json 与导出的设计资源
```

- 没有 WebView、React、CSS 皮肤，也不共享 UI 代码。
- 四个客户端共享 Rust 核心、FFI 契约、设计令牌与测试夹具。
- 桌面三端为第一阶段，Android 为第二阶段；Android 的游戏进程继续复用
  Amethyst/Pojav 系的 JRE、LWJGL 移植与渲染器。

## 当前状态

这是原生 UI 迁移后的**工作区初始化**：

- Rust workspace、`fg-ffi` ABI 版本/健康检查已可编译。
- 四个原生 App 骨架已建立：macOS SwiftUI、Windows WinUI 3、Linux GTK4、
  Android Compose。
- 旧 `ui/`、`src-tauri/` 和 Tauri 插件已删除。
- 各 App 目前只是空壳/占位页，下一阶段按 M0/M1 接入原生外壳与真实数据。

## 构建

Rust 核心：

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace
```

macOS（需要 Xcode 或完整 Swift 工具链；若 `xcode-select` 指向 CommandLineTools，
设置 `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`）：

```bash
swift build --package-path apps/macos
swift run --package-path apps/macos
```

Windows（需要 Windows 10 1809+、.NET 8 SDK）：

```powershell
dotnet build apps/windows/Fograin.Windows/Fograin.Windows.csproj
```

Linux（Ubuntu 24.04+）：

```bash
sudo apt install libgtk-4-dev libadwaita-1-dev build-essential pkg-config
cargo run --manifest-path apps/linux/Cargo.toml
```

Android：用 Android Studio 打开 `apps/android`，或安装 Android SDK/JDK 17 后：

```bash
./gradlew -p apps/android assembleDebug
```

`apps/linux` 是独立 Cargo workspace，避免 macOS/Windows 的
`cargo test --workspace` 去编译 GTK。

## 目录约定

- `crates/` 不依赖任何 UI 框架，也不包含平台句柄。
- `bindings/fg-ffi/` 是 UI 唯一入口；Linux GTK 客户端因同为 Rust 可直接依赖
  `crates/*`。
- `design/tokens.json` 是颜色、字号、间距、圆角的唯一来源。
- 每个 `apps/*` 自己拥有窗口、菜单、导航、快捷键、无障碍和发布流程。

日志与崩溃报告仍写入数据目录下的 `logs/`。开发时可用
`FOGRAIN_HOME=/tmp/fograin` 改变数据根目录。

## 设计令牌

修改 `design/tokens.json` 后运行 `python3 scripts/generate_tokens.py`，不要手改各客户端的生成文件。

## 许可

GPL-3.0-only，见 [LICENSE](LICENSE)。
