import AppKit
import FograinKit
import SwiftUI

/// Applies the AppKit-only window chrome the SwiftUI shell cannot express:
/// transparent hidden titlebar, native traffic lights over the sidebar, and
/// frame autosave. The pen draws a custom top bar inside the content, so the
/// system titlebar is kept but made invisible rather than replaced.
enum MainWindowConfiguration {
    static let identifier = NSUserInterfaceItemIdentifier("Fograin.MainWindow")

    static func apply(to window: NSWindow, title: String) {
        window.identifier = identifier
        window.title = title
        window.tabbingMode = .disallowed
        window.isMovableByWindowBackground = false
        window.contentMinSize = NSSize(
            width: FGLayout.windowMinWidth,
            height: FGLayout.windowMinHeight
        )
        window.collectionBehavior.insert(.fullScreenPrimary)
        window.backgroundColor = FograinGenerated.Color.background.nsColor
        window.isReleasedWhenClosed = false

        let autosaveName = "Fograin.MainWindow"
        if window.frameAutosaveName != autosaveName {
            let restored = window.setFrameUsingName(autosaveName)
            window.setFrameAutosaveName(autosaveName)
            if !restored {
                window.center()
            }
        }

        if let screen = window.screen ?? NSScreen.main {
            let visible = screen.visibleFrame
            if window.frame.minY < visible.minY || window.frame.maxY > visible.maxY {
                window.center()
            }
        }
    }
}

/// Finds the hosting window and configures it exactly once.
struct WindowConfigurator: NSViewRepresentable {
    let title: String
    let showsActions: Bool

    func makeCoordinator() -> Coordinator {
        Coordinator(title: title)
    }

    func makeNSView(context: Context) -> NSView {
        let probe = WindowProbeView()
        probe.onWindow = { window in
            if !context.coordinator.configured {
                context.coordinator.configured = true
                MainWindowConfiguration.apply(to: window, title: context.coordinator.title)
            } else {
                window.title = context.coordinator.title
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                ToolbarGroupInstaller.shared.install(on: window, showsActions: showsActions)
            }
        }
        return probe
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        context.coordinator.title = title
        guard let window = nsView.window else { return }
        DispatchQueue.main.async { [weak window] in
            guard let window else { return }
            if window.title != title {
                window.title = title
            }
            ToolbarGroupInstaller.shared.install(on: window, showsActions: showsActions)
        }
    }

    final class Coordinator {
        var configured = false
        var title: String

        init(title: String) {
            self.title = title
        }
    }
}

private final class WindowProbeView: NSView {
    var onWindow: ((NSWindow) -> Void)?

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        guard let window else { return }
        DispatchQueue.main.async { [weak self] in
            self?.onWindow?(window)
        }
    }
}
