import FograinKit
import SwiftUI

struct RootView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        NavigationSplitView {
            SidebarView()
                .navigationSplitViewColumnWidth(
                    min: FGLayout.sidebarMin,
                    ideal: FGLayout.sidebarIdeal,
                    max: FGLayout.sidebarMax
                )
        } detail: {
            Group {
                DetailRouter(section: model.selection)
                    .id(model.selection)
                    .transition(.opacity)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .animation(.easeInOut(duration: 0.16), value: model.selection)
        }
        .navigationSplitViewStyle(.balanced)
        .background(FG.background)
        .background(WindowConfigurator(
            title: model.selection.title,
            showsActions: model.selection == .home || model.selection == .library
        ))
        .overlay(alignment: .bottom) {
            NoticeOverlay()
        }
        .toolbar {
            if model.selection == .home {
                ToolbarItem(id: "page-control", placement: .primaryAction) {
                    Picker("视图", selection: $model.homeViewMode) {
                        Image(systemName: "square.grid.2x2").tag(HomeViewMode.grid)
                        Image(systemName: "list.bullet").tag(HomeViewMode.list)
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                    .help("切换实例视图")
                }
            } else if model.selection == .library {
                ToolbarItem(id: "page-control", placement: .primaryAction) {
                    Picker("排序", selection: $model.librarySort) {
                        ForEach(LibrarySort.allCases) { option in
                            Text(option.title).tag(option)
                        }
                    }
                    .pickerStyle(.menu)
                    .labelsHidden()
                    .help("排序方式")
                }
            }

            if model.selection == .home || model.selection == .library {
                ToolbarItem(id: "library-search", placement: .primaryAction) {
                    NativeSearchField(
                        text: $model.libraryQuery,
                        placeholder: model.selection == .library
                            ? "搜索实例"
                            : "搜索实例、模组…",
                        focusRequest: model.searchFocusRequest,
                        onSubmit: { model.focusLibrarySearch() },
                        onFocusHandled: { model.searchFocusRequest = 0 }
                    )
                    .frame(width: 220)
                }
            }
        }
        .task {
            model.showStartupWarningIfNeeded()
        }
    }
}

private struct DetailRouter: View {
    let section: AppSection

    var body: some View {
        switch section {
        case .home:
            HomeView()
        case .library:
            LibraryView()
        case .settings:
            SettingsPage()
        case .about:
            AboutPage()
        case .servers, .downloads, .java, .accounts:
            PlaceholderPage(section: section)
        }
    }
}
