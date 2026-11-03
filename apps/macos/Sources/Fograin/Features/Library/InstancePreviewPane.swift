import FograinKit
import SwiftUI

struct InstancePreviewPane: View {
    let instance: Instance?
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Group {
            if let instance {
                content(for: instance)
            } else {
                ContentUnavailableView(
                    "未选择实例",
                    systemImage: "sidebar.right",
                    description: Text("在列表中选择一个实例查看详情。")
                )
                .frame(maxWidth: .infinity)
                .padding(.top, 120)
            }
        }
    }

    private func content(for instance: Instance) -> some View {
        VStack(alignment: .leading, spacing: FGSpacing.page) {
            PreviewBanner(instance: instance)

            HStack(spacing: FGSpacing.lg) {
                LoaderTile(loader: instance.loader, size: 40)

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: FGSpacing.xs) {
                        Text(instance.name)
                            .font(FGFont.headingBold)
                            .foregroundStyle(FG.textPrimary)
                            .lineLimit(1)
                        if instance.isFavorite {
                            Image(systemName: "star.fill")
                                .font(.system(size: 9))
                                .foregroundStyle(FG.warning)
                        }
                    }
                    Text("\(instance.loader.title) \(instance.loaderVersion) · Java 版 \(instance.minecraftVersion)")
                        .font(.system(size: 11.5))
                        .foregroundStyle(FG.textTertiary)
                        .lineLimit(1)
                }
            }

            HStack(spacing: FGSpacing.md) {
                Button {
                    model.startGame(instance)
                } label: {
                    Label("开始游戏", systemImage: "play.fill")
                }
                .controlSize(.regular)
                .fgCapsuleButton(prominent: true)
                .tint(FG.accent)

                iconButton("folder", label: "打开文件夹") {
                    model.openInstanceFolder(instance)
                }
                iconButton("doc.on.doc", label: "复制实例") {
                    model.showNotice("复制实例将在 M4 接入", symbol: "doc.on.doc")
                }
                iconButton("slider.horizontal.3", label: "管理实例") {
                    model.manageInstance(instance)
                }
            }

            Text("双击列表项进入实例管理")
                .font(.system(size: 11))
                .foregroundStyle(FG.textTertiary)

            FGSeparator()

            VStack(alignment: .leading, spacing: FGSpacing.ml) {
                ForEach(SampleData.details(for: instance)) { item in
                    DetailRow(item: item)
                }
            }

            if let mods = instance.modCount, mods > 0 {
                FGSeparator()
                updateNotice(for: instance)
            }
        }
    }

    private func iconButton(
        _ systemImage: String,
        label: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
        }
        .controlSize(.regular)
        .fgCapsuleButton(circular: true)
        .help(label)
        .accessibilityLabel(label)
    }

    private func updateNotice(for instance: Instance) -> some View {
        HStack(spacing: FGSpacing.md) {
            Image(systemName: "arrow.down.circle")
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(FG.accent)

            VStack(alignment: .leading, spacing: 2) {
                Text("3 个模组有更新")
                    .font(.system(size: 12.5, weight: .medium))
                    .foregroundStyle(FG.textPrimary)
                Text("Sodium、Iris、Lithium")
                    .font(.system(size: 11))
                    .foregroundStyle(FG.textTertiary)
                    .lineLimit(1)
            }

            Spacer(minLength: FGSpacing.md)

            Button("更新") {
                model.updateMods(instance)
            }
            .controlSize(.small)
            .fgCapsuleButton()
        }
    }
}

private struct PreviewBanner: View {
    let instance: Instance

    var body: some View {
        ZStack {
            if let image = AppImage.nsImage("hero-forest") {
                GeometryReader { proxy in
                    Image(nsImage: image)
                        .resizable()
                        .interpolation(.high)
                        .scaledToFill()
                        .frame(width: proxy.size.width, height: proxy.size.height)
                        .clipped()
                }
            } else {
                instance.palette.gradient
            }

            instance.palette.baseColor.opacity(0.18)

            LinearGradient(
                colors: [.clear, .black.opacity(0.18)],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        .frame(height: 150)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(FG.glassStroke, lineWidth: 1)
        )
    }
}
