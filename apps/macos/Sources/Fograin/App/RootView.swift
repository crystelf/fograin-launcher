import AppKit
import FograinKit
import SwiftUI

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @State private var windowWidth = RootView.initialWindowWidth
    @State private var toolbarReady = false

    private var settingsSheetInstance: Binding<Instance?> {
        Binding(
            get: {
                guard let id = model.isInstanceSettingsPresented else { return nil }
                return model.instances.first { $0.id == id }
            },
            set: { newValue in
                model.isInstanceSettingsPresented = newValue?.id
            }
        )
    }

    /// The principal toolbar area shrinks with the window. Keep the hidden
    /// label width in sync so the play capsule stays at the left edge without
    /// pushing the trailing search/actions into overflow.
    private var runLabelWidth: CGFloat {
        min(max(windowWidth - 970, 160), 420)
    }

    /// Use the real window content width for the first toolbar layout. Without
    /// this the hidden label starts at its default width and the glass capsule
    /// visibly slides in from the centre on first appearance.
    private static var initialWindowWidth: CGFloat {
        if let width = NSApplication.shared.windows.first?.contentView?.bounds.width,
           width > 0 {
            return width
        }
        return 1240
    }

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
        .background {
            GeometryReader { proxy in
                Color.clear
                    .onAppear {
                        windowWidth = proxy.size.width
                        toolbarReady = true
                    }
                    .onChange(of: proxy.size.width) { windowWidth = proxy.size.width }
            }
        }
        .overlay(alignment: .bottom) {
            NoticeOverlay()
        }
        .sheet(item: settingsSheetInstance) { instance in
            InstanceSettingsScreen(instance: instance)
                .environmentObject(model)
                .environment(\.locale, Locale(identifier: "zh_Hans_CN"))
                .environment(\.colorScheme, model.resolvedTheme.colorScheme)
        }
        .alert(item: $model.alert) { alert in
            Alert(
                title: Text(alert.title),
                message: Text(alert.combinedMessage),
                dismissButton: .default(Text("好"))
            )
        }
        .toolbar {
            if model.selection == .home, toolbarReady {
                if #available(macOS 26.0, *) {
                    ToolbarItem(id: "run-playstop", placement: .principal) {
                        playStopControl
                    }
                    .sharedBackgroundVisibility(.visible)
                } else {
                    ToolbarItem(id: "run-playstop", placement: .principal) {
                        playStopControl
                    }
                }

                if #available(macOS 26.0, *) {
                    ToolbarItem(id: "run-label", placement: .principal) {
                        HStack(spacing: 0) {
                            HomeToolbarStatusView(model: model)
                            Spacer(minLength: 0)
                        }
                        .frame(width: runLabelWidth, height: 30, alignment: .leading)
                    }
                    .sharedBackgroundVisibility(.hidden)
                }
            }

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

private extension RootView {
    var playStopControl: some View {
        HStack(spacing: 0) {
            Button {
                model.startCurrentGame()
            } label: {
                Image(systemName: "play.fill")
                    .font(.system(size: 15, weight: .semibold))
                    .frame(width: 30, height: 27)
            }
            .buttonStyle(RunPressStyle())
            .disabled(model.sessionRunning || model.isSessionLoading)

            Divider()
                .frame(height: 16)
                .opacity(0.55)

            Button {
                model.stopGame()
            } label: {
                Image(systemName: "stop.fill")
                    .font(.system(size: 14, weight: .semibold))
                    .frame(width: 30, height: 27)
                    .opacity(model.sessionRunning ? 1 : 0.35)
            }
            .buttonStyle(RunPressStyle())
            .disabled(!model.sessionRunning)
        }
        .frame(width: 64, height: 28)
    }
}

private struct RunPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.55 : 1)
            .scaleEffect(configuration.isPressed ? 0.92 : 1)
            .animation(.easeOut(duration: 0.08), value: configuration.isPressed)
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
        case .downloads:
            DownloadsScreen()
        case .servers, .java, .accounts:
            PlaceholderPage(section: section)
        }
    }
}
