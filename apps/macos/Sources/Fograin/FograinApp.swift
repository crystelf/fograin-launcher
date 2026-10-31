import SwiftUI

@main
struct FograinApp: App {
    var body: some Scene {
        WindowGroup("雾雨启动器") {
            ContentView()
                .frame(minWidth: 1120, minHeight: 720)
        }
        .defaultSize(width: 1400, height: 880)
        .windowResizability(.contentMinSize)
        .commands {
            CommandGroup(replacing: .newItem) {}
        }
    }
}
