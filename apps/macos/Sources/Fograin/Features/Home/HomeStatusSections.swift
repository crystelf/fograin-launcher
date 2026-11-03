import FograinKit
import SwiftUI

struct HomeStatusColumn: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: FGSpacing.page) {
            downloadsSection
            FGSeparator()
            runtimeSection
            FGSeparator()
            newsSection
        }
    }

    private var downloadsSection: some View {
        VStack(alignment: .leading, spacing: FGSpacing.xl) {
            HStack(spacing: FGSpacing.md) {
                Image(systemName: "arrow.down.circle")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(FG.accent)
                Text("下载队列")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(FG.textPrimary)
                Spacer(minLength: FGSpacing.md)
                Text("18.4 MB/s")
                    .font(.system(size: 11.5))
                    .foregroundStyle(FG.textSecondary)
                    .monospacedDigit()
            }

            ForEach(model.downloads) { task in
                VStack(alignment: .leading, spacing: FGSpacing.xl) {
                    HStack(spacing: FGSpacing.md) {
                        Text(task.title)
                            .font(FGFont.small)
                            .foregroundStyle(FG.textPrimary)
                            .lineLimit(1)
                        Spacer(minLength: FGSpacing.md)
                        Text(task.detail)
                            .font(.system(size: 11))
                            .foregroundStyle(FG.textTertiary)
                            .monospacedDigit()
                    }
                    ThinProgressBar(progress: task.progress)
                        .accessibilityLabel(task.title)
                }
            }
        }
    }

    private var runtimeSection: some View {
        VStack(alignment: .leading, spacing: FGSpacing.lg) {
            Text("运行环境")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(FG.textPrimary)

            KeyValueRow(
                label: "Java",
                value: "Temurin 21.0.5 · \(model.systemInfo.architecture)",
                checkmark: true
            )
            KeyValueRow(
                label: "内存分配",
                value: "6 GB / \(model.systemInfo.memoryText)",
                checkmark: true
            )
            KeyValueRow(
                label: "渲染",
                value: model.systemInfo.gpuText,
                checkmark: true
            )
        }
    }

    private var newsSection: some View {
        VStack(alignment: .leading, spacing: FGSpacing.xl) {
            Text("版本动态")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(FG.textPrimary)

            ForEach(model.news) { item in
                HStack(spacing: FGSpacing.md) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.title)
                            .font(.system(size: 12.5))
                            .foregroundStyle(FG.textPrimary)
                            .lineLimit(1)
                        Text(item.detail)
                            .font(.system(size: 11))
                            .foregroundStyle(FG.textTertiary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: FGSpacing.md)
                    Button("安装") {
                        model.installVersion(item)
                    }
                    .controlSize(.small)
                    .fgCapsuleButton()
                    .help("安装将在 M2 接入")
                }
            }
        }
    }
}
