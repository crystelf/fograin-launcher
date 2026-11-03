import FograinKit
import SwiftUI

struct SettingsPage: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: FGSpacing.page) {
                appearanceSection

                FGSeparator()

                startupSection

                Text("M1 只接入了外观设置；配置由 Rust 核心读写，原生客户端不重复实现。")
                    .font(FGFont.caption)
                    .foregroundStyle(FG.textTertiary)
            }
            .padding(.horizontal, FGLayout.contentPadding)
            .padding(.top, FGSpacing.section)
            .padding(.bottom, FGLayout.contentPadding)
            .frame(maxWidth: 760, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .background(FG.background)
        .navigationSubtitle("外观与全局设置")
    }

    private var appearanceSection: some View {
        FlatSection("外观", detail: "立即生效") {
            VStack(alignment: .leading, spacing: FGSpacing.xxl) {
                HStack(spacing: FGSpacing.lg) {
                    Text("主题")
                        .font(FGFont.small)
                        .foregroundStyle(FG.textSecondary)
                        .frame(width: 96, alignment: .leading)

                    Picker("主题", selection: themeSelection) {
                        ForEach(ThemePreference.allCases) { theme in
                            Label(theme.title, systemImage: theme.symbolName).tag(theme)
                        }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                    .frame(width: 320)

                    Spacer(minLength: 0)

                    Text("当前：\(model.resolvedTheme.title)")
                        .font(FGFont.caption)
                        .foregroundStyle(FG.textTertiary)
                }

                VStack(alignment: .leading, spacing: FGSpacing.md) {
                    Text("设置写入 \(model.settings.configPath)")
                        .font(FGFont.caption)
                        .foregroundStyle(FG.textTertiary)
                        .textSelection(.enabled)
                        .lineLimit(2)
                        .truncationMode(.middle)

                    HStack(spacing: FGSpacing.md) {
                        Button("在访达中显示配置") {
                            model.revealConfig()
                        }
                        .controlSize(.small)

                        Button("打开数据目录") {
                            model.openDataRoot()
                        }
                        .controlSize(.small)
                    }
                }
            }
        }
    }

    private var startupSection: some View {
        FlatSection("启动与网络", detail: "其余设置项将在后续里程碑接入") {
            VStack(spacing: FGSpacing.lg) {
                SettingsMilestoneRow(title: "语言", value: "简体中文", milestone: "M10")
                FGSeparator()
                SettingsMilestoneRow(title: "下载源", value: "自动 · 官方优先", milestone: "M2")
                FGSeparator()
                SettingsMilestoneRow(title: "最大内存", value: "6 GB", milestone: "M6")
                FGSeparator()
                SettingsMilestoneRow(title: "并发下载", value: "8", milestone: "M2")
                FGSeparator()
                SettingsMilestoneRow(title: "日志级别", value: "Info", milestone: "M10")
            }
        }
    }

    private var themeSelection: Binding<ThemePreference> {
        Binding(
            get: { model.themePreference },
            set: { model.setTheme($0) }
        )
    }
}

private struct SettingsMilestoneRow: View {
    let title: String
    let value: String
    let milestone: String

    var body: some View {
        HStack(spacing: FGSpacing.lg) {
            Text(title)
                .font(FGFont.small)
                .foregroundStyle(FG.textSecondary)
                .frame(width: 96, alignment: .leading)

            Text(value)
                .font(FGFont.small)
                .foregroundStyle(FG.textPrimary)

            Spacer(minLength: FGSpacing.md)

            Text(milestone)
                .font(FGFont.caption)
                .foregroundStyle(FG.textTertiary)
                .padding(.horizontal, FGSpacing.md)
                .padding(.vertical, FGSpacing.xxs)
                .background(Capsule().fill(FG.glass))
                .overlay(Capsule().strokeBorder(FG.glassStroke, lineWidth: 1))
        }
    }
}
