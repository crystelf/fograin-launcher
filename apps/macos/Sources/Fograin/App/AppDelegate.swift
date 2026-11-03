import AppKit

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationWillFinishLaunching(_ notification: Notification) {
        NSWindow.allowsAutomaticWindowTabbing = false
        NSApplication.shared.setActivationPolicy(.regular)

        // Apply the persisted theme before SwiftUI creates the first window, so
        // the launch does not flash in the wrong appearance (§4.1.1).
        AppModel.shared.synchronizeAppearance()

        if let icon = AppAssets.icon {
            NSApplication.shared.applicationIconImage = icon
        }
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        // SwiftUI names the application menu after the executable; the product
        // name is Chinese. A real .app bundle replaces this in M10.
        DispatchQueue.main.async {
            NSApplication.shared.mainMenu?.items.first?.title = "雾雨启动器"
            NSApplication.shared.windows.first {
                $0.identifier == MainWindowConfiguration.identifier
            }?.title = AppModel.shared.selection.title
            let isSnapshot = ProcessInfo.processInfo.environment["FOGRAIN_SNAPSHOT"] != nil
            if !isSnapshot {
                NSApplication.shared.windows.first?.makeKeyAndOrderFront(nil)
                NSRunningApplication.current.activate(options: [.activateAllWindows])
                if #available(macOS 14.0, *) {
                    NSApplication.shared.activate()
                } else {
                    NSApplication.shared.activate(ignoringOtherApps: true)
                }
            }
        }

        // Reapply once the window exists; this also refreshes dynamic token
        // colors that were first resolved before the window appearance changed.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
            AppModel.shared.synchronizeAppearance()
        }

        #if DEBUG
        SnapshotSupport.scheduleIfRequested(model: AppModel.shared)
        #endif
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }
}
