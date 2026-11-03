import FograinKit
import SwiftUI

/// Native `⌘,` preferences window. The in-app Settings page mirrors this and
/// will grow the full settings surface in M10.
struct PreferencesScene: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: FGSpacing.section) {
            VStack(alignment: .leading, spacing: FGSpacing.lg) {
                Text("外观")
                    .font(FGFont.subheadBold)
                    .foregroundStyle(FG.textPrimary)

                Picker("主题", selection: themeSelection) {
                    ForEach(ThemePreference.allCases) { theme in
                        Label(theme.title, systemImage: theme.symbolName).tag(theme)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()

                Text("当前使用\(model.resolvedTheme.title)外观。偏好写入 \(model.settings.configPath)。")
                    .font(FGFont.caption)
                    .foregroundStyle(FG.textTertiary)
                    .lineLimit(2)
                    .truncationMode(.middle)
            }

            FGSeparator()

            VStack(alignment: .leading, spacing: FGSpacing.md) {
                Text("启动器")
                    .font(FGFont.subheadBold)
                    .foregroundStyle(FG.textPrimary)

                HStack(spacing: FGSpacing.md) {
                    Text("数据目录")
                        .font(FGFont.small)
                        .foregroundStyle(FG.textSecondary)
                    Text(model.settings.dataRoot)
                        .font(FGFont.small)
                        .foregroundStyle(FG.textPrimary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .textSelection(.enabled)
                    Spacer(minLength: FGSpacing.md)
                    Button("在访达中显示") {
                        model.openDataRoot()
                    }
                    .controlSize(.small)
                }

                HStack(spacing: FGSpacing.md) {
                    Text("Rust ABI")
                        .font(FGFont.small)
                        .foregroundStyle(FG.textSecondary)
                    Text("v\(model.settings.abiVersion) · \(model.settings.platform)")
                        .font(FGFont.small)
                        .foregroundStyle(FG.textPrimary)
                    Spacer()
                    Button("关于雾雨启动器") {
                        model.navigate(to: .about)
                    }
                    .controlSize(.small)
                }
            }

            Spacer(minLength: 0)
        }
        .padding(FGSpacing.page)
        .frame(width: 480, height: 300, alignment: .topLeading)
        .background(FG.background)
    }

    private var themeSelection: Binding<ThemePreference> {
        Binding(
            get: { model.themePreference },
            set: { model.setTheme($0) }
        )
    }
}
