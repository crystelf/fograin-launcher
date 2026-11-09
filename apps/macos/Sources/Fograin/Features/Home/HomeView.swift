import FograinKit
import SwiftUI

enum HomeViewMode: String, CaseIterable {
    case list
    case grid
}

struct HomeView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: FGLayout.blockGap) {
                    if let instance = model.currentInstance {
                        HeroCard(instance: instance)
                    } else {
                        EmptyHomeHero()
                    }
                    RecentInstancesSection(mode: model.homeViewMode)
                }
                .padding(.horizontal, FGLayout.contentPadding)
                .padding(.top, FGSpacing.lg)
                .padding(.bottom, FGLayout.contentPadding)
            }
            .frame(maxWidth: .infinity)
            .fgScrollEdgeEffect()

            Rectangle()
                .fill(FG.separator)
                .frame(width: 1)

            ScrollView {
                HomeStatusColumn()
                    .padding(.horizontal, FGLayout.contentPadding)
                    .padding(.top, FGSpacing.lg)
                    .padding(.bottom, FGLayout.contentPadding)
            }
            .frame(width: FGLayout.statusColumnWidth)
        }
        .background(FG.background)
        .overlay {
            if model.isInitialLoading {
                ZStack {
                    FG.background
                    ProgressView("正在读取实例…")
                        .controlSize(.small)
                }
            }
        }
        .navigationSubtitle(model.homeSubtitle)
    }
}

/// Shown before the first instance exists; routes to the download page.
struct EmptyHomeHero: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: FGSpacing.md) {
            Text("还没有实例")
                .font(FGFont.title)
                .foregroundStyle(FG.textPrimary)
            Text("通过下载页选择 Minecraft 版本、加载器并安装，几分钟后就能开始游戏。")
                .font(FGFont.body)
                .foregroundStyle(FG.textSecondary)
            Button {
                model.openAddInstance()
            } label: {
                Label("添加实例", systemImage: "plus")
            }
            .controlSize(.large)
            .fgCapsuleButton(prominent: true)
            .tint(FG.accent)
            .keyboardShortcut(.defaultAction)
            .padding(.top, FGSpacing.sm)
        }
        .frame(maxWidth: .infinity, minHeight: FGLayout.heroHeight - 40, alignment: .leading)
        .padding(FGSpacing.page)
        .background(
            RoundedRectangle(cornerRadius: FGRadius.card, style: .continuous)
                .fill(FG.surface)
        )
        .overlay(
            RoundedRectangle(cornerRadius: FGRadius.card, style: .continuous)
                .strokeBorder(FG.separator, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: FGRadius.card, style: .continuous))
    }
}
