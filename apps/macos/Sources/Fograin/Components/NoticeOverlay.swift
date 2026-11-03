import SwiftUI

/// Transient capsule pinned above the window bottom edge, Xcode-style.
struct NoticeOverlay: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            if let notice = model.notice {
                HStack(spacing: FGSpacing.md) {
                    Image(systemName: notice.symbol)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(tint(for: notice.tone))
                    Text(notice.message)
                        .font(FGFont.small)
                        .foregroundStyle(FG.textPrimary)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, FGSpacing.lg)
                .padding(.vertical, FGSpacing.ml)
                .background(.regularMaterial, in: Capsule())
                .overlay(Capsule().strokeBorder(FG.separator, lineWidth: 1))
                .shadow(color: .black.opacity(0.18), radius: 14, y: 5)
                .padding(.bottom, FGSpacing.section)
                .transition(
                    reduceMotion
                        ? .opacity
                        : .move(edge: .bottom).combined(with: .opacity)
                )
                .id(notice.id)
            }
        }
        .animation(
            reduceMotion
                ? .easeOut(duration: 0.15)
                : .spring(response: 0.34, dampingFraction: 1),
            value: model.notice?.id
        )
        .allowsHitTesting(false)
    }

    private func tint(for tone: Notice.Tone) -> Color {
        switch tone {
        case .info: FG.accent
        case .success: FG.success
        case .warning: FG.warning
        }
    }
}
