import FograinKit
import SwiftUI

struct HeroCard: View {
    let instance: Instance
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ZStack(alignment: .topLeading) {
            SceneImage()

            LinearGradient(
                stops: [
                    .init(color: .black.opacity(0.12), location: 0.0),
                    .init(color: .clear, location: 0.28),
                    .init(color: .clear, location: 0.46),
                    .init(color: .black.opacity(0.84), location: 1.0),
                ],
                startPoint: .top,
                endPoint: .bottom
            )

            VStack(alignment: .leading, spacing: 0) {
                HeroStatusPill(text: "已就绪 · 资源校验完成")
                    .padding(.top, 22)

                Spacer(minLength: FGSpacing.section)

                launchBar
                    .frame(height: 100)
            }
            .padding(.horizontal, FGSpacing.page)
        }
        .frame(height: FGLayout.heroHeight)
        .clipShape(RoundedRectangle(cornerRadius: FGRadius.card, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: FGRadius.card, style: .continuous)
                .strokeBorder(FGImage.chromeStroke, lineWidth: 1)
        )
    }

    private var launchBar: some View {
        HStack(alignment: .center, spacing: FGSpacing.section) {
            InstanceTile(instance: instance, size: 56)

            VStack(alignment: .leading, spacing: FGSpacing.sm) {
                Text(instance.name)
                    .font(FGFont.title)
                    .foregroundStyle(FGImage.primary)
                    .lineLimit(1)

                HStack(spacing: FGSpacing.xl) {
                    HeroMetaLabel(
                        symbol: "gamepad",
                        text: "Java 版 \(instance.minecraftVersion)"
                    )
                    HeroMetaLabel(
                        symbol: "square.stack.3d.up.fill",
                        text: instance.loaderVersion.isEmpty
                            ? instance.loader.title
                            : "\(instance.loader.title) \(instance.loaderVersion)"
                    )
                    if let mods = instance.modCount {
                        HeroMetaLabel(symbol: "puzzlepiece.fill", text: "\(mods) 个模组")
                    }
                }
            }

            Spacer(minLength: FGSpacing.xl)

            Button {
                model.manageInstance(instance)
            } label: {
                Image(systemName: "slider.horizontal.3")
            }
            .controlSize(.large)
            .fgCapsuleButton(circular: true)
            .help("实例设置将在 M4 接入")
            .accessibilityLabel("实例设置")

            Button {
                model.startGame(instance)
            } label: {
                Label("开始游戏", systemImage: "play.fill")
            }
            .controlSize(.large)
            .fgCapsuleButton(prominent: true)
            .tint(FG.accent)
            .keyboardShortcut(.defaultAction)
            .help("开始游戏 (↩)")
        }
    }
}

private struct SceneImage: View {
    var body: some View {
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
            LinearGradient(
                colors: [FG.surfaceRaised, FG.background],
                startPoint: .top,
                endPoint: .bottom
            )
        }
    }
}
