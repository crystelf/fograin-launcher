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
                    HeroCard(instance: model.currentInstance)
                    RecentInstancesSection(mode: model.homeViewMode)
                }
                .padding(.horizontal, FGLayout.contentPadding)
                .padding(.top, FGSpacing.lg)
                .padding(.bottom, FGLayout.contentPadding)
            }
            .frame(maxWidth: .infinity)

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
        .navigationSubtitle(model.homeSubtitle)

    }
}
