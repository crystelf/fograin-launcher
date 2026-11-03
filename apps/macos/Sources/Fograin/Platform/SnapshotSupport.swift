#if DEBUG
import AppKit

/// Development-only helper: `FOGRAIN_SNAPSHOT=/tmp/shot.png` renders the window
/// to PNG files after launch. Used by the M1 visual checks because the shell has
/// no bundled `.app` and CI has no screen recording permission.
///
/// Optional environment variables:
/// - `FOGRAIN_SNAPSHOT_SECTION` — home, library, settings, about, servers…
/// - `FOGRAIN_SNAPSHOT_QUERY` — prefill the library search field
/// - `FOGRAIN_SNAPSHOT_DELAY` — seconds to wait after launch (default 1.2)
/// - `FOGRAIN_SNAPSHOT_EXIT=1` — terminate after writing the files
///
/// Liquid Glass windows cannot be captured with `cacheDisplay`; pass
/// `FOGRAIN_DISABLE_GLASS=1` to use the bordered capsule fallback while
/// reviewing layout screenshots.
@MainActor
enum SnapshotSupport {
    static func scheduleIfRequested(model: AppModel) {
        let environment = ProcessInfo.processInfo.environment
        guard let path = environment["FOGRAIN_SNAPSHOT"] else { return }

        if let rawSection = environment["FOGRAIN_SNAPSHOT_SECTION"],
           let section = AppSection(rawValue: rawSection) {
            model.selection = section
        }
        if let query = environment["FOGRAIN_SNAPSHOT_QUERY"] {
            model.libraryQuery = query
        }

        let delay = Double(environment["FOGRAIN_SNAPSHOT_DELAY"] ?? "") ?? 1.2
        let exitAfter = environment["FOGRAIN_SNAPSHOT_EXIT"] == "1"

        Task { @MainActor in
            try? await Task.sleep(for: .seconds(delay))
            capture(to: path, exitAfter: exitAfter)
        }
    }

    private static func capture(to path: String, exitAfter: Bool) {
        let window = NSApplication.shared.windows.first { $0.isVisible }
            ?? NSApplication.shared.windows.first

        guard let view = window?.contentView else {
            log("snapshot: no window content view", exitAfter: exitAfter)
            return
        }

        write(view, to: path)

        // The theme frame also contains the titlebar, traffic lights and toolbar,
        // which are outside contentView.
        if let frameView = view.superview {
            let chromePath = path.replacingOccurrences(of: ".png", with: "-chrome.png")
            write(frameView, to: chromePath)
        }

        if exitAfter {
            NSApplication.shared.terminate(nil)
        }
    }

    private static func write(_ view: NSView, to path: String) {
        view.layoutSubtreeIfNeeded()
        guard let representation = view.bitmapImageRepForCachingDisplay(in: view.bounds) else {
            print("snapshot: cannot allocate bitmap for \(path)")
            return
        }

        view.effectiveAppearance.performAsCurrentDrawingAppearance {
            view.cacheDisplay(in: view.bounds, to: representation)
        }

        guard let data = representation.representation(using: .png, properties: [:]) else {
            print("snapshot: PNG encoding failed for \(path)")
            return
        }

        do {
            try data.write(to: URL(fileURLWithPath: path))
            print("snapshot: wrote \(path) (\(representation.pixelsWide)x\(representation.pixelsHigh))")
        } catch {
            print("snapshot: write failed for \(path): \(error)")
        }
    }

    private static func log(_ message: String, exitAfter: Bool) {
        FileHandle.standardError.write(Data((message + "\n").utf8))
        if exitAfter {
            NSApplication.shared.terminate(nil)
        }
    }
}
#endif
