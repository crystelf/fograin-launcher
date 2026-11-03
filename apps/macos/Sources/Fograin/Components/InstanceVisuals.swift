import FograinKit
import SwiftUI

/// Instance artwork: bitmap when the instance has one, otherwise the instance
/// symbol over the palette gradient from `design/tokens.json`.
struct InstanceTile: View {
    let instance: Instance
    var size: CGFloat = 34

    private var cornerRadius: CGFloat {
        size * 0.235
    }

    var body: some View {
        ZStack {
            instance.palette.gradient

            if let imageName = instance.iconImageName,
               let image = AppImage.nsImage(imageName) {
                Image(nsImage: image)
                    .resizable()
                    .interpolation(.high)
                    .scaledToFill()
            } else {
                Image(systemName: instance.iconSymbol)
                    .font(.system(size: size * 0.47, weight: .medium))
                    .foregroundStyle(Color.white.opacity(0.92))
                    .shadow(color: .black.opacity(0.2), radius: 2, y: 1)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .strokeBorder(FG.glassStroke, lineWidth: 1)
        )
        .accessibilityHidden(true)
    }
}

/// Loader badge used by the library rows and preview heading. The pen draws a
/// letter tile (F / N / M) and a hammer for Forge.
struct LoaderTile: View {
    let loader: LoaderKind
    var size: CGFloat = 30

    private var cornerRadius: CGFloat {
        size * 0.235
    }

    var body: some View {
        ZStack {
            loader.tileColor

            LinearGradient(
                colors: [.white.opacity(0.20), .clear],
                startPoint: .top,
                endPoint: .bottom
            )

            if loader.usesGlyphIcon {
                Image(systemName: "hammer.fill")
                    .font(.system(size: size * 0.46, weight: .bold))
                    .foregroundStyle(loader.onTile)
            } else {
                Text(loader.tileGlyph)
                    .font(.system(size: size * 0.47, weight: .heavy))
                    .foregroundStyle(loader.onTile)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .strokeBorder(Color.white.opacity(0.15), lineWidth: 1)
        )
        .accessibilityLabel(loader.title)
    }
}

/// Status capsule on the hero artwork.
struct HeroStatusPill: View {
    let text: String

    var body: some View {
        HStack(spacing: FGSpacing.sm) {
            Circle()
                .fill(FG.success)
                .frame(width: 6, height: 6)
            Text(text)
                .font(.system(size: 11.5, weight: .medium))
                .foregroundStyle(FGImage.primary)
        }
        .padding(.horizontal, FGSpacing.lg)
        .frame(height: 28)
        .background(Capsule().fill(FGImage.chrome))
        .overlay(Capsule().strokeBorder(Color.white.opacity(0.14), lineWidth: 1))
        .fixedSize()
    }
}

struct HeroMetaLabel: View {
    let symbol: String
    let text: String

    var body: some View {
        HStack(spacing: FGSpacing.xs) {
            Image(systemName: symbol)
                .font(.system(size: 12, weight: .medium))
            Text(text)
                .font(FGFont.small)
        }
        .foregroundStyle(FGImage.secondary)
    }
}

struct KeyValueRow: View {
    let label: String
    let value: String
    var checkmark = false

    var body: some View {
        HStack(spacing: FGSpacing.md) {
            Text(label)
                .font(FGFont.small)
                .foregroundStyle(FG.textSecondary)
            Spacer(minLength: FGSpacing.md)
            Text(value)
                .font(FGFont.small)
                .foregroundStyle(FG.textPrimary)
                .lineLimit(1)
                .truncationMode(.middle)
            if checkmark {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(FG.success)
            }
        }
    }
}

struct DetailRow: View {
    let item: DetailItem

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: FGSpacing.md) {
            Text(item.label)
                .font(FGFont.small)
                .foregroundStyle(FG.textTertiary)
                .frame(width: 64, alignment: .leading)
            Text(item.value)
                .font(FGFont.small)
                .foregroundStyle(FG.textPrimary)
                .lineLimit(1)
            Spacer(minLength: 0)
        }
    }
}

/// A flat 4pt progress bar, matching the pen download rows.
struct ThinProgressBar: View {
    let progress: Double

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(FG.glass)
                Capsule()
                    .fill(FG.accent)
                    .frame(width: max(0, proxy.size.width * min(max(progress, 0), 1)))
            }
        }
        .frame(height: 4)
        .accessibilityValue(Text("\(Int(progress * 100))%"))
    }
}
