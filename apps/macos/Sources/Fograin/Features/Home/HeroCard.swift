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
                Spacer(minLength: 0)
                heroInfo
                    .padding(.bottom, 22)
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

    private var heroInfo: some View {
        HStack(alignment: .center, spacing: FGSpacing.section) {
            InstanceTile(instance: instance, size: 56)
            instanceText(compact: false)
            Spacer(minLength: 0)
        }
    }

    private func instanceText(compact: Bool) -> some View {
        VStack(alignment: .leading, spacing: compact ? FGSpacing.xs : FGSpacing.sm) {
            Text(instance.name)
                .font(compact ? FGFont.headingBold : FGFont.title)
                .foregroundStyle(FGImage.primary)
                .lineLimit(1)
                .truncationMode(.tail)

            HStack(spacing: compact ? FGSpacing.md : FGSpacing.xl) {
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
                if !compact, let mods = instance.modCount {
                    HeroMetaLabel(symbol: "puzzlepiece.fill", text: "\(mods) 个模组")
                }
            }
            .lineLimit(1)
        }
        .layoutPriority(1)
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
