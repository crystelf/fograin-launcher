import FograinKit
import SwiftUI

struct PlaceholderPage: View {
    let section: AppSection

    var body: some View {
        ScrollView {
            VStack(spacing: FGSpacing.page) {
                ZStack {
                    Circle()
                        .fill(FG.glass)
                        .frame(width: 76, height: 76)
                    Image(systemName: section.symbol)
                        .font(.system(size: 30, weight: .light))
                        .foregroundStyle(FG.accent)
                }

                Text(section.title)
                    .font(FGFont.title)
                    .foregroundStyle(FG.textPrimary)

                Text(section.milestone?.detail ?? "这一页将在后续里程碑接入。")
                    .font(FGFont.body)
                    .foregroundStyle(FG.textSecondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 440)

                if let milestone = section.milestone {
                    MilestoneChip(text: "\(milestone.name) 接入")
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 120)
            .padding(.bottom, 40)
        }
        .background(FG.background)
        .navigationSubtitle(section.pageSubtitle)
    }
}

struct MilestoneChip: View {
    let text: String

    var body: some View {
        Text(text)
            .font(FGFont.captionMedium)
            .foregroundStyle(FG.accent)
            .padding(.horizontal, FGSpacing.ml)
            .padding(.vertical, FGSpacing.sm)
            .background(Capsule().fill(FG.accent.opacity(0.12)))
            .overlay(Capsule().strokeBorder(FG.accent.opacity(0.22), lineWidth: 1))
    }
}
