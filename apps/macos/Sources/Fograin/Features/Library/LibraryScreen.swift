import FograinKit
import SwiftUI

struct LibraryView: View {
    @EnvironmentObject private var model: AppModel

    private var sections: [LibrarySection] {
        LibraryOrganizer.sections(from: model.filteredInstances(), sort: model.librarySort)
    }

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            ScrollView {
                LibraryListView(sections: sections)
                    .padding(.horizontal, FGSpacing.xxl)
                    .padding(.top, FGSpacing.lg)
                    .padding(.bottom, FGLayout.contentPadding)
            }
            .frame(maxWidth: .infinity)

            Rectangle()
                .fill(FG.separator)
                .frame(width: 1)

            ScrollView {
                InstancePreviewPane(instance: model.selectedInstance)
                    .padding(.horizontal, FGLayout.contentPadding)
                    .padding(.top, FGSpacing.lg)
                    .padding(.bottom, FGLayout.contentPadding)
            }
            .frame(width: FGLayout.previewColumnWidth)
            .background(FG.surface.opacity(0.30))
        }
        .background(FG.background)
        .navigationSubtitle(model.librarySubtitle)

    }
}
