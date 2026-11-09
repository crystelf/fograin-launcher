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
/// - `FOGRAIN_SNAPSHOT_SIZE=1120x720` — resize the window before capturing
/// - `FOGRAIN_SNAPSHOT_SETTINGS=<instance-id>` — open its 游戏设置 sheet
/// - `FOGRAIN_SNAPSHOT_INSTALL_SHEET=1` — present the install dialog
/// - `FOGRAIN_SNAPSHOT_LOADER=fabric` — preselect a loader in that dialog
/// - `FOGRAIN_SNAPSHOT_ERROR=1` — present a sample error alert
/// - `FOGRAIN_SNAPSHOT_ACTION=download` — start a download for
///   `FOGRAIN_SNAPSHOT_VERSION` (default rd-132211) and capture the result
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
        if let raw = environment["FOGRAIN_SNAPSHOT_CATEGORY"],
           let category = DownloadsCategory(rawValue: raw) {
            model.downloadsCategory = category
        }
        if let raw = environment["FOGRAIN_SNAPSHOT_CHANNEL"],
           let channel = DownloadVersionChannel(rawValue: raw) {
            model.downloadVersionChannel = channel
        }
        if let raw = environment["FOGRAIN_SNAPSHOT_FILTER"],
           let filter = ContentLoaderFilter(rawValue: raw) {
            model.downloadContentLoader = filter
        }
        if let settingsID = environment["FOGRAIN_SNAPSHOT_SETTINGS"] {
            model.isInstanceSettingsPresented = settingsID
        }
        if environment["FOGRAIN_SNAPSHOT_ERROR"] == "1" {
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(1))
                model.showError(
                    "无法安装 26.3",
                    message: "网络请求失败，请检查网络或代理设置。",
                    detail: "error.network: connection timed out"
                )
            }
        }
        if environment["FOGRAIN_SNAPSHOT_INSTALL_SHEET"] == "1" {
            model.openInstallSheet(for: environment["FOGRAIN_SNAPSHOT_VERSION"] ?? "26.3")
        }
        if environment["FOGRAIN_SNAPSHOT_ACTION"] == "download" {
            let version = environment["FOGRAIN_SNAPSHOT_VERSION"] ?? "rd-132211"
            model.downloadSelectedVersionId = version
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(0.8))
                try? await model.startGameInstall(version: version, instanceName: "")
            }
        }

        let delay = Double(environment["FOGRAIN_SNAPSHOT_DELAY"] ?? "") ?? 1.2
        let exitAfter = environment["FOGRAIN_SNAPSHOT_EXIT"] == "1"
        let requestedSize = Self.parseSize(environment["FOGRAIN_SNAPSHOT_SIZE"])

        Task { @MainActor in
            NSApplication.shared.activate(ignoringOtherApps: true)
            if let requestedSize,
               let window = NSApplication.shared.windows.first(where: { $0.isVisible }) {
                window.setContentSize(requestedSize)
                window.makeKeyAndOrderFront(nil)
            }
            try? await Task.sleep(for: .seconds(delay))
            CGWarpMouseCursorPosition(CGPoint(x: 8, y: 8))
            capture(to: path, exitAfter: exitAfter)
        }
    }

    private static func parseSize(_ raw: String?) -> NSSize? {
        guard let raw else { return nil }
        let parts = raw.lowercased().split(separator: "x")
        guard parts.count == 2,
              let width = Double(parts[0]),
              let height = Double(parts[1]) else {
            return nil
        }
        return NSSize(width: width, height: height)
    }

    private static func capture(to path: String, exitAfter: Bool) {
        let mainWindow = NSApplication.shared.windows.first { $0.isVisible }
            ?? NSApplication.shared.windows.first
        // A SwiftUI `.sheet` attaches as a child window; capture it when the
        // snapshot requested the settings page.
        let window = mainWindow?.attachedSheet ?? mainWindow

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
            // A SwiftUI `.sheet` can keep the modal run loop alive and block
            // `terminate`; the debug snapshot only needs the PNG on disk.
            exit(0)
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
            DispatchQueue.main.async {
                NSApplication.shared.terminate(nil)
            }
        }
    }
}
#endif
