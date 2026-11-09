import AppKit
import FograinKit
import Foundation
import SwiftUI
import os

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

/// Prominent modal error. Replaces the transient capsule for failures so a
/// failed install or launch cannot silently look healthy.
struct AppAlert: Identifiable, Equatable {
    let id = UUID()
    let title: String
    let message: String
    let detail: String?

    var combinedMessage: String {
        guard let detail, !detail.isEmpty else { return message }
        let trimmed = detail.count > 480 ? String(detail.prefix(480)) + "…" : detail
        return "\(message)\n\n\(trimmed)"
    }
}

/// One running download shown by the home queue and the downloads page.
struct DownloadRow: Identifiable, Equatable {
    let id: String
    let title: String
    var fraction: Double
    var speed: Double
    var state: String

    var speedText: String {
        speed <= 0 ? "—" : InstanceFormat.byteRate(speed) + "/s"
    }
}

@MainActor
final class AppModel: ObservableObject {
    static let shared = AppModel()

    // Navigation and library state
    @Published var selection: AppSection = .home
    @Published var currentInstanceID = ""
    @Published var libraryQuery = ""
    @Published var librarySelection: String?
    @Published var searchFocusRequest = 0
    @Published var homeViewMode: HomeViewMode = .list
    @Published var librarySort: LibrarySort = .recent

    // M4 server data
    @Published private(set) var instances: [Instance] = []
    @Published private(set) var downloads: [DownloadRow] = []
    @Published private(set) var appSnapshot: CoreAppSnapshot?
    @Published private(set) var gameConfig: CoreGameConfig
    @Published private(set) var sessionRunning = false
    @Published private(set) var sessionPID: Int?
    @Published private(set) var sessionInstanceID: String?
    @Published private(set) var isSessionLoading = false
    private var stopRequested = false
    @Published private(set) var isInitialLoading = true
    @Published var alert: AppAlert?

    // Shell state
    @Published private(set) var notice: Notice?
    @Published private(set) var themePreference: ThemePreference
    @Published private(set) var resolvedTheme: ResolvedTheme
    /// Bumped after the native window appearance changes so the view tree is
    /// rebuilt with the new resolved colors.
    @Published private(set) var themeRevision = 0
    @Published private(set) var settings: CoreSettingsSnapshot

    // Static fixtures kept for pages the core does not feed yet (accounts).
    let account = SampleData.account
    let news = SampleData.news
    let systemInfo = SystemInfo.current()

    private var systemAppearanceObserver: NSObjectProtocol?
    private var noticeTask: Task<Void, Never>?
    private var startupWarning: String?
    private var pollTask: Task<Void, Never>?
    private var gameLogBuffer: [String: [String]] = [:]
    private static let logger = Logger(subsystem: "dev.fograin.launcher", category: "app")

    init(settings: CoreSettingsSnapshot = CoreBridge.settingsSnapshot()) {
        self.settings = settings
        self.themePreference = settings.theme
        self.resolvedTheme = AppModel.resolveTheme(settings.theme)
        self.gameConfig = CoreGameConfig(
            username: "Steve",
            memoryMb: 0,
            jvmArgs: "",
            resolution: nil,
            fullscreen: false,
            showLog: false,
            autoCompleteResources: true
        )
        FG.theme = self.resolvedTheme

        if let error = settings.configError {
            self.startupWarning = "读取 launcher.toml 失败，已使用默认外观。\(error.localizedDescription)"
        }

        observeSystemAppearance()
        Task { [weak self] in
            await self?.refreshCoreData()
        }
        startEventPump()
    }

    deinit {
        pollTask?.cancel()
        noticeTask?.cancel()
        if let systemAppearanceObserver {
            DistributedNotificationCenter.default.removeObserver(systemAppearanceObserver)
        }
    }

    // MARK: - Derived state

    var currentInstance: Instance? {
        instances.first { $0.id == currentInstanceID } ?? instances.first
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

    /// Tasks that are still working; completed/failed rows stay in `downloads`
    /// for history but must not keep the sidebar badge or queue alive.
    var activeDownloads: [DownloadRow] {
        downloads.filter { $0.state == "running" || $0.state == "paused" }
    }

    var activeDownloadCount: Int {
        activeDownloads.count
    }

    /// Aggregate speed for the home queue header (`18.4 MB/s` / 空闲).
    /// True when this instance's files are being installed right now.
    func isDownloading(_ instance: Instance) -> Bool {
        if pendingDownloadInstanceID == instance.id { return true }
        return activeDownloads.contains { $0.title.contains(instance.minecraftVersion) }
    }

    /// Home runtime card: effective heap plus physical memory.
    var memorySummary: String {
        let memoryMB = gameConfig.memoryMb
        let physical = systemInfo.memoryText
        if memoryMB == 0 {
            return "自动 · 物理内存 \(physical)"
        }
        return "\(max(1, memoryMB / 1024)) GB / \(physical)"
    }

    var javaSummary: String {
        guard let count = appSnapshot?.javaCount else { return "扫描中…" }
        return count == 0 ? "未发现 Java" : "已发现 \(count) 个运行时"
    }

    var downloadsSpeedText: String {
        let total = activeDownloads.reduce(0.0) { $0 + $1.speed }
        if total <= 0 { return activeDownloads.isEmpty ? "空闲" : "—" }
        return InstanceFormat.byteRate(total) + "/s"
    }

    func filteredInstances() -> [Instance] {
        LibraryFilter(query: libraryQuery).apply(to: instances)
    }

    // MARK: - Core services

    func refreshCoreData() async {
        do {
            let payload = try await CoreBridge.rpcAsync("instances.list")
            instances = CoreWire.decodeArray(CoreInstanceRow.self, from: payload, key: "result")
                .compactMap(Self.convert(row:))
            if currentInstanceID.isEmpty {
                currentInstanceID = instances.first?.id ?? ""
            }
            if librarySelection == nil {
                librarySelection = instances.first?.id
            }
        } catch {
            Self.logger.error("instances.list failed: \(String(describing: error), privacy: .public)")
            if instances.isEmpty {
                showError("无法读取实例列表", error)
            }
        }

        if let payload = try? await CoreBridge.rpcAsync("app.snapshot") {
            appSnapshot = CoreWire.decodeMerged(CoreAppSnapshot.self, from: payload)
        }
        if let payload = try? await CoreBridge.rpcAsync("gameConfig.get") {
            gameConfig = CoreWire.decodeMerged(CoreGameConfig.self, from: payload) ?? gameConfig
        }
        await refreshDownloadsFromTasks()
        isInitialLoading = false
    }

    private func refreshDownloadsFromTasks() async {
        guard let payload = try? await CoreBridge.rpcAsync("tasks.list") else {
            return
        }
        let rows: [CoreTaskRow] = CoreWire.decodeArray(CoreTaskRow.self, from: payload, key: "result")
        downloads = rows.map { row in
            DownloadRow(
                id: row.id,
                title: Self.taskTitle(row.name),
                fraction: row.fraction,
                speed: row.speed,
                state: row.state
            )
        }
    }

    static func taskTitle(_ name: String) -> String {
        InstanceFormat.taskTitle(name)
    }

    private static func convert(row: CoreInstanceRow) -> Instance? {
        let loader = LoaderKind(rawValue: normalizeLoader(row.loaderKind)) ?? .vanilla
        return Instance(
            id: row.id,
            name: row.name.isEmpty ? row.id : row.name,
            loader: loader,
            loaderVersion: row.loaderVersion,
            minecraftVersion: row.minecraft,
            modCount: row.modCount,
            sizeBytes: row.sizeBytes,
            lastPlayed: row.lastPlayedMs.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) },
            isFavorite: row.favorite,
            iconImageName: nil,
            iconSymbolName: nil,
            palette: TilePalette.palette(for: row.id),
            playTimeMinutes: row.playSeconds > 0 ? Int((row.playSeconds / 60).clampedToInt()) : nil,
            isInstalled: row.installed
        )
    }

    private static func normalizeLoader(_ raw: String) -> String {
        let lowered = raw.lowercased()
        guard !lowered.isEmpty, lowered != "vanilla" else { return "vanilla" }
        return lowered
    }

    private static func describe(_ error: Error) -> String {
        if case let CoreBridgeError.rejected(payload) = error {
            return payload.userMessage
        }
        return (error as? CoreBridgeError)?.message ?? error.localizedDescription
    }

    /// Shows a blocking error dialog. Failures must never be a 2.8s capsule:
    /// the user has to see why an install or launch did not work.
    func showError(_ title: String, _ error: Error) {
        Self.logger.error("\(title, privacy: .public): \(String(describing: error), privacy: .public)")
        if case let CoreBridgeError.rejected(payload) = error {
            alert = AppAlert(
                title: title,
                message: payload.userMessage,
                detail: payload.detail
            )
        } else {
            alert = AppAlert(
                title: title,
                message: (error as? CoreBridgeError)?.message ?? error.localizedDescription,
                detail: nil
            )
        }
    }

    func showError(_ title: String, message: String, detail: String? = nil) {
        Self.logger.error("\(title, privacy: .public): \(message, privacy: .public)")
        alert = AppAlert(title: title, message: message, detail: detail)
    }

    /// One Rust-side command with error surfacing through the notice capsule.
    @discardableResult
    func callCore(_ method: String, params: [String: Any] = [:], notice: String? = nil) -> [String: Any]? {
        do {
            let payload = try CoreBridge.rpc(method, params: params)
            if let notice {
                showNotice(notice, symbol: "checkmark.circle", tone: .success)
            }
            Task { [weak self] in
                await self?.refreshCoreData()
            }
            return payload
        } catch {
            showError(notice ?? "操作失败", error)
            return nil
        }
    }

    private func startEventPump() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(0.25))
                guard let self else { return }
                await self.drainEvents()
            }
        }
    }

    private func drainEvents() async {
        let events = CoreBridge.nextEvents()
        guard !events.isEmpty else { return }
        for raw in events {
            guard let event = CoreEvent.parse(raw) else { continue }
            handle(event)
        }
        await refreshDownloadsFromTasks()
    }

    private func handle(_ event: CoreEvent) {
        switch event {
        case .taskProgress:
            break
        case let .taskFinished(taskId, name, state, detail):
            let title = Self.taskTitle(name)
            let isOurDownload = taskId == pendingDownloadTaskID
            switch state {
            case "succeeded":
                showNotice("\(title) 完成", symbol: "checkmark.circle", tone: .success)
                Task { [weak self] in
                    await self?.finishGameDownload()
                }
            case "failed":
                let message = detail.first ?? "未知原因"
                showError("\(title) 失败", message: message, detail: detail.dropFirst().joined(separator: "\n"))
                if isOurDownload { isGameDownloadRunning = false }
            case "cancelled":
                showNotice("\(title) 已取消", symbol: "xmark.circle", tone: .info)
                if isOurDownload { isGameDownloadRunning = false }
            default:
                break
            }
            Task { [weak self] in
                await self?.refreshCoreData()
            }
        case let .gameStarted(instanceId, pid):
            stopRequested = false
            sessionInstanceID = instanceId
            sessionPID = pid
            sessionRunning = true
            isSessionLoading = false
            showNotice("游戏已启动，进程 \(pid ?? 0)", symbol: "play.fill", tone: .success)
            updateDockBadge("▸")
        case .gameWindowReady:
            updateDockBadge(nil)
        case let .gameLine(instanceId, text):
            bufferGameLine(instanceId: instanceId, text: text)
        case let .gameExited(instanceId, exitCode, seconds):
            let wasStopped = stopRequested
            stopRequested = false
            sessionRunning = false
            isSessionLoading = false
            sessionPID = nil
            sessionInstanceID = nil
            updateDockBadge(nil)
            if wasStopped {
                showNotice("游戏已停止", symbol: "stop.circle.fill", tone: .info)
            } else if exitCode == 0 {
                showNotice("游戏已退出（\(InstanceFormat.playTime(Int(seconds / 60)))）", symbol: "checkmark.circle", tone: .success)
            } else {
                showError(
                    "游戏异常退出",
                    message: "游戏进程以代码 \(exitCode.map(String.init) ?? "signal") 结束。",
                    detail: "请查看实例目录 logs/ 下的最新日志，或在实例设置中打开日志与崩溃报告（M9 完整接入）。"
                )
            }
            Task { [weak self] in
                await self?.refreshCoreData()
            }
        }
    }

    /// After the game files land, walk the user to 游戏设置 of the freshly
    /// created instance (PCL-style: download → 设置 → 启动).
    private func finishGameDownload() async {
        isGameDownloadRunning = false
        pendingDownloadTaskID = nil
        guard let instanceID = pendingDownloadInstanceID else { return }
        pendingDownloadInstanceID = nil
        await refreshCoreData()
        guard instances.contains(where: { $0.id == instanceID }) else { return }
        currentInstanceID = instanceID
        librarySelection = instanceID
        selection = .library
        isInstanceSettingsPresented = instanceID
    }

    func recentGameLog(instanceId: String) -> [String] {
        gameLogBuffer[instanceId] ?? []
    }

    private func bufferGameLine(instanceId: String, text: String) {
        var lines = gameLogBuffer[instanceId] ?? []
        lines.append(text)
        if lines.count > 240 {
            lines.removeFirst(lines.count - 240)
        }
        gameLogBuffer[instanceId] = lines
    }

    private func updateDockBadge(_ text: String?) {
        NSApplication.shared.dockTile.badgeLabel = text
    }

    // MARK: - Downloads page state (HMCL/PCL flow)

    @Published var downloadsCategory: DownloadsCategory = .game
    @Published var downloadsQuery = ""
    @Published var downloadVersionChannel: DownloadVersionChannel = .release
    @Published var downloadContentLoader: ContentLoaderFilter = .all
    @Published private(set) var downloadVersions: [CoreVersionEntry] = []
    @Published private(set) var isLoadingDownloadVersions = false
    @Published private(set) var downloadVersionsChannel: DownloadVersionChannel?
    @Published var downloadSelectedVersionId: String?
    @Published private(set) var downloadPlan: CorePlanPreview?
    @Published private(set) var downloadVersionsError: String?
    @Published private(set) var downloadPlanError: String?
    private var downloadVersionsGeneration = 0
    private var loadingDownloadChannel: DownloadVersionChannel?
    /// Instance queued by the install sheet; consumed when install finishes.
    @Published private(set) var pendingDownloadInstanceID: String?
    /// The specific install task the download page is waiting for.
    @Published private(set) var pendingDownloadTaskID: String?
    @Published private(set) var isGameDownloadRunning = false
    /// Modal loader/version/name dialog, owned by the model so the download
    /// page and toolbar can present it from anywhere.
    @Published var isGameInstallSheetPresented = false

    /// Loads the manifest for the selected channel after the page has
    /// appeared. The Rust call blocks its own thread, so it runs off the main
    /// actor; changing channels starts a new generation and drops stale
    /// responses.
    func loadDownloadVersions() async {
        if downloadVersionsChannel == downloadVersionChannel {
            await refreshDownloadPlan()
            return
        }
        await fetchDownloadVersions()
    }

    /// Called by the titlebar channel dropdown. Clears the old list immediately
    /// so the page shows its loading state instead of stale versions.
    func reloadDownloadVersions() async {
        downloadVersions = []
        downloadVersionsChannel = nil
        downloadSelectedVersionId = nil
        downloadPlan = nil
        downloadPlanError = nil
        downloadVersionsError = nil
        downloadsQuery = ""
        await fetchDownloadVersions()
    }

    private func fetchDownloadVersions() async {
        let channel = downloadVersionChannel
        if loadingDownloadChannel == channel {
            return
        }
        loadingDownloadChannel = channel
        downloadVersionsGeneration += 1
        let generation = downloadVersionsGeneration
        isLoadingDownloadVersions = true
        downloadVersionsError = nil

        defer {
            if generation == downloadVersionsGeneration {
                loadingDownloadChannel = nil
                isLoadingDownloadVersions = false
            }
        }

        do {
            let payload = try await CoreBridge.rpcAsync(
                "versions.list",
                params: [
                    "kind": channel.kindValue,
                    "limit": 500,
                    "refresh": false,
                ]
            )
            guard generation == downloadVersionsGeneration,
                  channel == downloadVersionChannel else { return }

            let array = payload["versions"] as? [[String: Any]] ?? []
            downloadVersions = array.compactMap(CoreVersionEntry.init(object:))
            downloadVersionsChannel = channel
            downloadSelectedVersionId = downloadVersions.first?.id
            if downloadVersions.isEmpty {
                downloadPlan = nil
                downloadPlanError = nil
            } else {
                await refreshDownloadPlan()
            }
        } catch {
            guard generation == downloadVersionsGeneration,
                  channel == downloadVersionChannel else { return }
            downloadVersionsError = Self.describe(error)
            showError("无法读取版本清单", error)
        }
    }

    func openInstallSheet(for version: String) {
        if !version.isEmpty {
            downloadSelectedVersionId = version
        }
        isGameInstallSheetPresented = true
    }

    func refreshDownloadPlan() async {
        guard let version = downloadSelectedVersionId else {
            downloadPlan = nil
            downloadPlanError = nil
            return
        }
        downloadPlanError = nil
        do {
            let payload = try await CoreBridge.rpcAsync(
                "version.plan",
                params: ["version": version]
            )
            guard version == downloadSelectedVersionId else { return }
            downloadPlan = CorePlanPreview(object: payload)
        } catch {
            guard version == downloadSelectedVersionId else { return }
            downloadPlan = nil
            downloadPlanError = Self.describe(error)
        }
    }

    /// Safe directory id for a display name. CJK names keep their display name
    /// and fall back to a generic id; collisions get a numeric suffix so the
    /// download page never fails just because an instance already exists.
    func uniqueInstanceID(name: String, version: String) -> String {
        let sanitized = InstanceFormat.sanitizedInstanceID(name)
        let base = sanitized.isEmpty
            ? (InstanceFormat.sanitizedInstanceID(version).isEmpty ? "instance" : InstanceFormat.sanitizedInstanceID(version))
            : sanitized
        guard instances.contains(where: { $0.id == base }) else { return base }
        var suffix = 2
        while instances.contains(where: { $0.id == "\(base)-\(suffix)" }) {
            suffix += 1
        }
        return "\(base)-\(suffix)"
    }

    /// Starts the download after the install sheet confirms loader, version and
    /// name. No per-instance detail options live here: after the finished event
    /// the launcher jumps to 游戏设置 (PCL behaviour).
    func startGameInstall(version: String, instanceName: String) async throws {
        if isInitialLoading {
            await refreshCoreData()
        }
        guard !isGameDownloadRunning else {
            throw CoreBridgeError.rejected(
                CoreErrorPayload(
                    kind: "conflict",
                    messageKey: "error.task.already_running",
                    detail: "已有下载任务在进行，请等待完成或取消。"
                )
            )
        }
        let name = instanceName.trimmingCharacters(in: .whitespaces)
        let id = uniqueInstanceID(name: name, version: version)
        let response = try await CoreBridge.rpcAsync(
            "install.start",
            params: [
                "version": version,
                "instanceId": id,
                "instanceName": name.isEmpty ? id : name,
            ]
        )
        pendingDownloadTaskID = response["taskId"] as? String
        pendingDownloadInstanceID = id
        isGameDownloadRunning = true
        downloadsCategory = .game
        selection = .downloads
        showNotice("已开始安装 \(version)", symbol: "arrow.down.circle", tone: .info)
        Task { [weak self] in
            await self?.refreshCoreData()
        }
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
            librarySelection = currentInstance?.id
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
            showError("无法保存外观设置", error)
        }
    }

    /// Applies the stored preference to AppKit and forces a color re-resolve.
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

    // MARK: - Commands and instance actions

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

    /// ⌘N / 新建实例: routes to the download page (HMCL/PCL behaviour).
    func newInstance() {
        openAddInstance()
    }

    /// `添加实例` 与「下载」页共用一个入口。
    func openAddInstance() {
        downloadsCategory = .game
        selection = .downloads
        Task { [weak self] in
            await self?.loadDownloadVersions()
        }
    }

    func importModpack() {
        showNotice("整合包导入将在 M9 接入", symbol: "shippingbox")
    }

    /// M4: launch goes through the Rust core and needs installed files.
    func startGame(_ instance: Instance) {
        currentInstanceID = instance.id
        guard instance.isInstalled else {
            if isDownloading(instance) {
                showNotice("“\(instance.name)”的资源仍在下载，请稍候再启动", symbol: "arrow.down.circle", tone: .info)
            } else {
                Task { [weak self] in
                    await self?.completeResources(for: instance)
                }
            }
            return
        }
        guard !sessionRunning else {
            showError("无法启动游戏", message: "已经有一个游戏在运行了。")
            return
        }
        guard !isSessionLoading else {
            return
        }
        Task { [weak self] in
            await self?.launchGame(instance)
        }
    }

    private func launchGame(_ instance: Instance) async {
        isSessionLoading = true
        do {
            _ = try await CoreBridge.rpcAsync(
                "launch.start",
                params: ["instanceId": instance.id, "username": gameConfig.username]
            )
            // Keep the progress spinner until `game.started` arrives; the event
            // handler clears `isSessionLoading`.
        } catch {
            isSessionLoading = false
            showError("无法启动“\(instance.name)”", error)
        }
    }

    /// PCL-style: pressing play on an incomplete instance starts its resource
    /// download and jumps to the queue instead of dead-ending.
    func completeResources(for instance: Instance) async {
        guard !isGameDownloadRunning else {
            showNotice("已有下载任务在进行", symbol: "arrow.down.circle", tone: .info)
            return
        }
        do {
            let response = try await CoreBridge.rpcAsync(
                "install.start",
                params: [
                    "version": instance.minecraftVersion,
                    "instanceId": instance.id,
                    "instanceName": instance.name,
                ]
            )
            pendingDownloadInstanceID = instance.id
            pendingDownloadTaskID = response["taskId"] as? String
            isGameDownloadRunning = true
            selection = .downloads
            showNotice("正在补全“\(instance.name)”的游戏资源", symbol: "arrow.down.circle", tone: .info)
            Task { [weak self] in
                await self?.refreshCoreData()
            }
        } catch {
            showError("无法补全“\(instance.name)”的资源", error)
        }
    }

    func manageInstance(_ instance: Instance) {
        currentInstanceID = instance.id
        librarySelection = instance.id
        // PCL: 双击/管理入口直达「游戏设置」。
        isInstanceSettingsPresented = instance.id
    }

    func manageCurrentInstance() {
        guard let currentInstance else { return }
        manageInstance(currentInstance)
    }

    func startCurrentGame() {
        guard let currentInstance else { return }
        startGame(currentInstance)
    }


    func openInstanceFolder(_ instance: Instance) {
        callCore("instances.dir", params: ["id": instance.id])
        let home = appSnapshot?.dataRoot ?? settings.dataRoot
        let url = URL(fileURLWithPath: "\(home)/instances/\(instance.id)", isDirectory: true)
        NSWorkspace.shared.activateFileViewerSelecting([url])
    }

    func updateMods(_ instance: Instance) {
        showNotice("批量更新将在 M8 接入", symbol: "arrow.triangle.2.circlepath")
    }

    func installVersion(_ news: NewsItem) {
        callCore(
            "install.start",
            params: ["version": news.title],
            notice: "已开始安装 \(news.title)"
        )
    }

    func toggleFavorite(_ instance: Instance) {
        callCore(
            "instances.favorite",
            params: ["id": instance.id, "favorite": !instance.isFavorite]
        )
    }

    /// Persists one instance setting (value `nil` inherits `[game]`).
    func configureInstance(id: String, setting: String, value: Any?) {
        do {
            let updates: [[String: Any]]
            if let value {
                updates = [["setting": setting, "value": value]]
            } else {
                updates = [["setting": setting, "value": NSNull()]]
            }
            try CoreBridge.rpc("instances.settings.set", params: ["id": id, "updates": updates])
            Task { [weak self] in
                await self?.refreshCoreData()
            }
        } catch {
            showError("设置保存失败", error)
        }
    }

    /// Registers an external `.minecraft` directory without moving files.
    func chooseMinecraftDirectory() {
        DirectoryPanel.present(
            title: "导入 .minecraft 目录",
            message: "选择一个现有的 .minecraft 目录。文件保留在原处，只登记为实例游戏目录。",
            prompt: "选择目录"
        ) { [weak self] url in
            guard let url, let self else { return }
            MainActor.assumeIsolated {
                self.callCore(
                    "instances.import",
                    params: ["directory": url.path],
                    notice: "已导入“\(url.lastPathComponent)”"
                )
            }
        }
    }

    func deleteInstance(_ instance: Instance) {
        callCore("instances.delete", params: ["id": instance.id])
    }

    func stopGame() {
        guard sessionRunning else {
            showNotice("没有正在运行的游戏", symbol: "stop.circle", tone: .info)
            return
        }
        stopRequested = true
        guard let payload = callCore("session.stop", params: [:]) else {
            stopRequested = false
            return
        }
        if (payload["stopped"] as? Bool) == false {
            stopRequested = false
            showNotice("没有正在运行的游戏", symbol: "stop.circle", tone: .info)
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

    // MARK: - Sheets

    /// Sheet hosts the game settings screen when non-`nil`.
    @Published var isInstanceSettingsPresented: String?

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

private extension UInt64 {
    func clampedToInt() -> Int {
        self > UInt64(Int.max) ? Int.max : Int(self)
    }
}

enum TilePalette {
    /// Assigns tile palettes in the pen's order so every instance gets a stable
    /// accent color derived from its id.
    static func palette(for id: String) -> Instance.Palette {
        var hash = 5381
        for scalar in id.unicodeScalars {
            hash = ((hash << 5) &+ hash) &+ Int(scalar.value)
        }
        let palettes = Instance.Palette.allCases
        return palettes[abs(hash) % palettes.count]
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
