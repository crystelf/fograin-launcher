import FograinKit
import SwiftUI

struct AboutPage: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: FGSpacing.page) {
                identity

                FGSeparator()

                FlatSection("数据目录", detail: "由 Rust 核心解析") {
                    VStack(alignment: .leading, spacing: FGSpacing.lg) {
                        PathRow(label: "数据根目录", path: model.settings.dataRoot)
                        FGSeparator()
                        PathRow(label: "配置文件", path: model.settings.configPath)
                        Button("在访达中显示") {
                            model.openDataRoot()
                        }
                        .controlSize(.small)
                    }
                }

                FGSeparator()

                FlatSection("构建") {
                    VStack(spacing: FGSpacing.lg) {
                        KeyValueRow(label: "界面", value: "SwiftUI + AppKit")
                        FGSeparator()
                        KeyValueRow(label: "核心", value: "Rust · fg-* crates")
                        FGSeparator()
                        KeyValueRow(label: "FFI", value: "C ABI v\(model.settings.abiVersion)")
                        FGSeparator()
                        KeyValueRow(label: "平台", value: model.settings.platform)
                        FGSeparator()
                        KeyValueRow(label: "应用版本", value: model.settings.appVersion)
                    }
                }

                FGSeparator()

                FlatSection("开源与致谢") {
                    VStack(alignment: .leading, spacing: FGSpacing.lg) {
                        AcknowledgementRow(
                            name: "HMCL / HMCLCore",
                            detail: "GPL-3.0 · 下载候选源、Forge processors 与崩溃规则的实现参考"
                        )
                        FGSeparator()
                        AcknowledgementRow(
                            name: "PCL.Core",
                            detail: "Apache-2.0 · Java 扫描、Yggdrasil 客户端与 McPing 的实现参考"
                        )
                        FGSeparator()
                        AcknowledgementRow(
                            name: "fograin.pen",
                            detail: "布局与信息架构参考；四个原生客户端各自实现，不共享 UI 代码"
                        )
                        FGSeparator()
                        Text("完整的第三方许可列表将在 M10 用 cargo-about 与 license-checker 生成。")
                            .font(FGFont.caption)
                            .foregroundStyle(FG.textTertiary)
                    }
                }

                Text("Minecraft 是 Mojang Studios 的商标。雾雨启动器与 Mojang、Microsoft 无关联。")
                    .font(FGFont.caption)
                    .foregroundStyle(FG.textTertiary)
            }
            .padding(.horizontal, FGLayout.contentPadding)
            .padding(.top, FGSpacing.section)
            .padding(.bottom, FGLayout.contentPadding)
            .frame(maxWidth: 820, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .background(FG.background)
        .navigationSubtitle("版本、构建与致谢")
    }

    private var identity: some View {
        HStack(alignment: .top, spacing: FGSpacing.page) {
            if let icon = AppAssets.icon {
                Image(nsImage: icon)
                    .resizable()
                    .interpolation(.high)
                    .frame(width: 72, height: 72)
            }

            VStack(alignment: .leading, spacing: FGSpacing.md) {
                Text("雾雨启动器")
                    .font(FGFont.title)
                    .foregroundStyle(FG.textPrimary)

                Text("Fograin Launcher · Minecraft Java 版")
                    .font(FGFont.small)
                    .foregroundStyle(FG.textSecondary)

                HStack(spacing: FGSpacing.md) {
                    InfoChip(text: "版本 \(model.settings.appVersion)")
                    InfoChip(text: "Rust ABI \(model.settings.abiVersion)")
                    InfoChip(text: "\(model.settings.platform) · SwiftUI")
                }

                Text("GPL-3.0-only · 原生 UI，不含 WebView")
                    .font(FGFont.caption)
                    .foregroundStyle(FG.textTertiary)
            }

            Spacer(minLength: 0)
        }
    }
}

private struct InfoChip: View {
    let text: String

    var body: some View {
        Text(text)
            .font(FGFont.caption)
            .foregroundStyle(FG.textSecondary)
            .padding(.horizontal, FGSpacing.md)
            .padding(.vertical, FGSpacing.xxs)
            .background(Capsule().fill(FG.glass))
            .overlay(Capsule().strokeBorder(FG.glassStroke, lineWidth: 1))
    }
}

private struct PathRow: View {
    let label: String
    let path: String

    var body: some View {
        VStack(alignment: .leading, spacing: FGSpacing.xxs) {
            Text(label)
                .font(FGFont.caption)
                .foregroundStyle(FG.textTertiary)
            Text(path)
                .font(FGFont.small)
                .foregroundStyle(FG.textPrimary)
                .textSelection(.enabled)
                .lineLimit(2)
                .truncationMode(.middle)
        }
    }
}

private struct AcknowledgementRow: View {
    let name: String
    let detail: String

    var body: some View {
        VStack(alignment: .leading, spacing: FGSpacing.xxs) {
            Text(name)
                .font(FGFont.captionMedium)
                .foregroundStyle(FG.textPrimary)
            Text(detail)
                .font(FGFont.caption)
                .foregroundStyle(FG.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
