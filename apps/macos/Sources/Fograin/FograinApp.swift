import FograinKit
import Foundation
import SwiftUI

@main
struct FograinApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var model = AppModel.shared

    var body: some Scene {
        WindowGroup("雾雨启动器") {
            RootView()
                .id(model.themeRevision)
                .environmentObject(model)
                .environment(\.locale, Locale(identifier: "zh_Hans_CN"))
                .preferredColorScheme(model.themePreference.resolved?.colorScheme)
                .environment(\.colorScheme, model.resolvedTheme.colorScheme)
                .frame(
                    minWidth: FGLayout.windowMinWidth,
                    minHeight: FGLayout.windowMinHeight
                )
        }
        .defaultSize(
            width: FGLayout.windowDefaultWidth,
            height: FGLayout.windowDefaultHeight
        )
        .windowResizability(.contentMinSize)
        .windowToolbarStyle(.unified)
        .commands {
            AppCommands(model: model)
        }

        Settings {
            PreferencesScene()
                .id(model.themeRevision)
                .environmentObject(model)
                .environment(\.locale, Locale(identifier: "zh_Hans_CN"))
                .preferredColorScheme(model.themePreference.resolved?.colorScheme)
                .environment(\.colorScheme, model.resolvedTheme.colorScheme)
        }
    }
}
