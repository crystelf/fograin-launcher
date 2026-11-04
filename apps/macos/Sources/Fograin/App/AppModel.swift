import AppKit
import FograinKit
import Foundation
import SwiftUI

/// Transient status capsule shown above the window bottom edge (Xcode-style).
struct Notice: Identifiable, Equatable {
    enum Tone: Equatable {
        case info
        case success
        case warning
    }

    let id = UUID()
    let message: String
    let symbol: String
    let tone: Tone
}

@MainActor
final class AppModel: ObservableObject {
    static let shared = AppModel()

    // Navigation and library state
    @Published var selection: AppSection = .home
    @Published var currentInstanceID: Instance.ID = SampleData.currentInstanceID
    @Published var libraryQuery = ""
    @Published var librarySelection: Instance.ID? = SampleData.currentInstanceID
    @Published var searchFocusRequest = 0
    @Published var homeViewMode: HomeViewMode = .list
    @Published var librarySort: LibrarySort = .recent

    // Shell state
    @Published private(set) var notice: Notice?
    @Published private(set) var themePreference: ThemePreference
    @Published private(set) var resolvedTheme: ResolvedTheme
    /// Bumped after the native window appearance changes so the view tree is
    /// rebuilt with the new resolved colors.
    @Published private(set) var themeRevision = 0
    @Published private(set) var settings: CoreSettingsSnapshot

    // Static M1 fixtures
    let instances = SampleData.instances
    let account = SampleData.account
    let downloads = SampleData.downloads
    let news = SampleData.news
    let systemInfo = SystemInfo.current()

    private var systemAppearanceObserver: NSObjectProtocol?
    private var noticeTask: Task<Void, Never>?
    private var startupWarning: String?

    init(settings: CoreSettingsSnapshot = CoreBridge.settingsSnapshot()) {
        self.settings = settings
        self.themePreference = settings.theme
        self.resolvedTheme = Self.resolveTheme(settings.theme)
        FG.theme = self.resolvedTheme

        if let error = settings.configError {
            self.startupWarning = "读取 launcher.toml 失败，已使用默认外观。\(error.localizedDescription)"
        }

        observeSystemAppearance()
    }

    deinit {
        noticeTask?.cancel()
        if let systemAppearanceObserver {
            DistributedNotificationCenter.default.removeObserver(systemAppearanceObserver)
        }
    }

    // MARK: - Derived state

    var currentInstance: Instance {
        instances.first { $0.id == currentInstanceID } ?? instances[0]
    }

    var selectedInstance: Instance? {
        guard let librarySelection else { return nil }
        return instances.first { $0.id == librarySelection }
    }

    var homeSubtitle: String {
        let lastPlayed = instances.compactMap(\.lastPlayed).max()
        return "\(instances.count) 个实例 · 上次游玩 \(InstanceFormat.relativeDate(lastPlayed))"
    }

    var librarySubtitle: String {
        "\(instances.count) 个实例 · 占用 \(InstanceFormat.totalSize(instances))"
    }

    func filteredInstances() -> [Instance] {
        LibraryFilter(query: libraryQuery).apply(to: instances)
    }

    // MARK: - Navigation

    func navigate(to section: AppSection) {
        selection = section
    }

    /// Single click on Home: make this the current instance and update the
    /// launch hero without leaving the page.
    func selectHomeInstance(_ instance: Instance) {
        currentInstanceID = instance.id
    }

    /// Double click / context menu: enter the instance library for this entry.
    func openInstance(_ instance: Instance) {
        currentInstanceID = instance.id
        librarySelection = instance.id
        selection = .library
    }

    /// ⌘F and the toolbar magnifier land here: always show the library table and
    /// put the caret in its native search field.
    func focusLibrarySearch() {
        selection = .library
        if librarySelection == nil {
            librarySelection = currentInstance.id
        }
        searchFocusRequest &+= 1
    }

    // MARK: - Theme

    func setTheme(_ preference: ThemePreference) {
        guard preference != themePreference else { return }

        do {
            let snapshot = try CoreBridge.setTheme(preference)
            settings = snapshot
            themePreference = snapshot.theme
            resolvedTheme = Self.resolveTheme(snapshot.theme)
            FG.theme = resolvedTheme
            NSApplication.shared.appearance = snapshot.theme.appearance
            themeRevision &+= 1
            showNotice(
                "外观已切换为\(snapshot.theme.title)",
                symbol: snapshot.theme.symbolName,
                tone: .success
            )
        } catch {
            let message = (error as? CoreBridgeError)?.message ?? error.localizedDescription
            showNotice(
                "无法保存外观设置：\(message)",
                symbol: "exclamationmark.triangle",
                tone: .warning
            )
        }
    }

    /// Applies the stored preference to AppKit and forces a color re-resolve.
    /// Dynamic NSColors can be captured before the window appearance is set at
    /// launch; rebuilding once after that point keeps the content in sync.
    func synchronizeAppearance() {
        NSApplication.shared.appearance = themePreference.appearance
        resolvedTheme = Self.resolveTheme(themePreference)
        FG.theme = resolvedTheme
        themeRevision &+= 1
    }

    func showStartupWarningIfNeeded() {
        guard let warning = startupWarning else { return }
        startupWarning = nil
        showNotice(warning, symbol: "exclamationmark.triangle", tone: .warning)
    }

    private func observeSystemAppearance() {
        systemAppearanceObserver = DistributedNotificationCenter.default.addObserver(
            forName: Notification.Name("AppleInterfaceThemeChangedNotification"),
            object: nil,
            queue: .main
        ) { [weak self] _ in
            // `queue: .main` already delivers on the main thread, so the handler
            // can take the main actor in place. A nested
            // `Task { @MainActor in ... }` would capture the *weak storage* of
            // `self`, which Swift 5.x rejects with "reference to captured var
            // 'self' in concurrently-executing code"; the binding below turns
            // `self` into an immutable, strongly-held local first.
            guard let self else { return }
            MainActor.assumeIsolated {
                self.systemAppearanceChanged()
            }
        }
    }

    private func systemAppearanceChanged() {
        guard themePreference == .system else { return }
        resolvedTheme = Self.currentSystemTheme()
        FG.theme = resolvedTheme
        themeRevision &+= 1
    }

    private static func resolveTheme(_ preference: ThemePreference) -> ResolvedTheme {
        preference.resolved ?? currentSystemTheme()
    }

    private static func currentSystemTheme() -> ResolvedTheme {
        let appearance = NSApplication.shared.effectiveAppearance
        return appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? .dark : .light
    }

    // MARK: - Commands and placeholder actions

    func showNotice(
        _ message: String,
        symbol: String = "info.circle",
        tone: Notice.Tone = .info
    ) {
        noticeTask?.cancel()
        notice = Notice(message: message, symbol: symbol, tone: tone)

        noticeTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2.8))
            guard !Task.isCancelled else { return }
            self?.notice = nil
        }
    }

    func newInstance() {
        showNotice("新建实例向导将在 M4 接入", symbol: "wand.and.stars")
    }

    func importModpack() {
        showNotice("整合包导入将在 M9 接入", symbol: "shippingbox")
    }

    func startGame(_ instance: Instance) {
        currentInstanceID = instance.id
        showNotice(
            "“\(instance.name)”的启动流程将在 M3 接入",
            symbol: "play.fill"
        )
    }

    func manageInstance(_ instance: Instance) {
        currentInstanceID = instance.id
        librarySelection = instance.id
        selection = .library
        showNotice("实例管理将在 M4 接入", symbol: "slider.horizontal.3")
    }

    func openInstanceFolder(_ instance: Instance) {
        showNotice("实例目录将在 M4 接入", symbol: "folder")
    }

    func updateMods(_ instance: Instance) {
        showNotice("批量更新将在 M8 接入", symbol: "arrow.triangle.2.circlepath")
    }

    func installVersion(_ news: NewsItem) {
        showNotice("“\(news.title)”的安装将在 M2 接入", symbol: "arrow.down.circle")
    }

    func toggleFavorite(_ instance: Instance) {
        showNotice("收藏状态将在 M4 写入实例元数据", symbol: "star")
    }

    func chooseMinecraftDirectory() {
        DirectoryPanel.present(
            title: "导入 .minecraft 目录",
            message: "选择一个现有的 .minecraft 目录。M4 会把它登记为外部游戏目录，不迁移文件。",
            prompt: "选择目录"
        ) { [weak self] url in
            // Same rule as `observeSystemAppearance()`: bind `self` and the URL
            // to immutable locals before entering main-actor code so no weak
            // capture var crosses into a concurrently-executing closure.
            guard let url, let self else { return }
            MainActor.assumeIsolated {
                self.showNotice(
                    "已选择“\(url.lastPathComponent)”。导入将在 M4 接入。",
                    symbol: "folder.badge.plus",
                    tone: .success
                )
            }
        }
    }

    func openDataRoot() {
        openFolder(settings.dataRoot, createIfNeeded: true)
    }

    func revealConfig() {
        let configURL = URL(fileURLWithPath: settings.configPath)
        guard FileManager.default.fileExists(atPath: configURL.path) else {
            showNotice("launcher.toml 尚未创建，切换外观后会生成", symbol: "doc.text")
            return
        }
        NSWorkspace.shared.activateFileViewerSelecting([configURL])
    }

    private func openFolder(_ path: String, createIfNeeded: Bool) {
        let url = URL(fileURLWithPath: path, isDirectory: true)
        do {
            if createIfNeeded {
                try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
            }
            NSWorkspace.shared.open(url)
        } catch {
            showNotice("无法打开目录：\(error.localizedDescription)", symbol: "folder.badge.questionmark", tone: .warning)
        }
    }
}

extension ResolvedTheme {
    var colorScheme: ColorScheme {
        self == .dark ? .dark : .light
    }
}

extension ThemePreference {
    var appearance: NSAppearance? {
        switch self {
        case .system: nil
        case .light: NSAppearance(named: .aqua)
        case .dark: NSAppearance(named: .darkAqua)
        }
    }
}
