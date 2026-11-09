import FograinKit
import SwiftUI

/// Top-level categories of the download page (§13). Only 游戏 is wired in M4;
/// the other tabs keep their milestone so the structure matches the pen.
enum DownloadsCategory: String, CaseIterable, Identifiable {
    case game
    case mod
    case modpack
    case resourcepack
    case shaderpack
    case datapack

    public var id: String { rawValue }

    var title: String {
        switch self {
        case .game: "游戏版本"
        case .mod: "模组"
        case .modpack: "整合包"
        case .resourcepack: "资源包"
        case .shaderpack: "光影包"
        case .datapack: "数据包"
        }
    }

    /// Compact label for the capsule switcher; the full title stays in the
    /// content header and accessible labels.
    var shortTitle: String {
        switch self {
        case .game: "游戏"
        case .mod: "模组"
        case .modpack: "整合包"
        case .resourcepack: "资源包"
        case .shaderpack: "光影"
        case .datapack: "数据包"
        }
    }

    var symbol: String {
        switch self {
        case .game: "cube"
        case .mod: "puzzlepiece"
        case .modpack: "shippingbox"
        case .resourcepack: "paintpalette"
        case .shaderpack: "sun.max"
        case .datapack: "doc.text"
        }
    }

    var milestone: String? {
        switch self {
        case .game: nil
        case .mod, .resourcepack, .shaderpack, .datapack: "M8"
        case .modpack: "M9"
        }
    }
}

/// 下载页 following HMCL/PCL flow: the category switcher is a native segmented
/// Picker in the titlebar (same Liquid Glass toolbar treatment as the home
/// page), the game tab exposes the version list, and loader / version / name
/// are chosen in the install sheet. Memory, JVM and display options live in
/// 游戏设置 — not here.
struct DownloadsScreen: View {
    @EnvironmentObject private var model: AppModel
    @State private var categoryPickerWidth: CGFloat = 400

    var body: some View {
        GeometryReader { proxy in
            // Below this width the queue column would squeeze the version list
            // and detail pane; collapse it instead of pushing the sidebar out.
            let showsQueue = proxy.size.width >= 820
            HStack(alignment: .top, spacing: 0) {
                middleColumn
                    .frame(minWidth: 0, maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)

                if showsQueue {
                    Rectangle()
                        .fill(FG.separator)
                        .frame(width: 1)
                    queueColumn
                        .frame(width: min(300, max(240, proxy.size.width * 0.28)))
                }
            }
        }
        .background(FG.background)
        .navigationSubtitle("浏览与安装")
        .task {
            await model.loadDownloadVersions()
        }
        .onChange(of: model.downloadSelectedVersionId) {
            Task { [weak model] in
                await model?.refreshDownloadPlan()
            }
        }
        .onChange(of: model.downloadVersionChannel) {
            Task { [weak model] in
                await model?.reloadDownloadVersions()
            }
        }
        .sheet(isPresented: $model.isGameInstallSheetPresented) {
            GameInstallSheet(initialVersionID: model.downloadSelectedVersionId ?? "")
                .environmentObject(model)
                .environment(\.locale, Locale(identifier: "zh_Hans_CN"))
                .environment(\.colorScheme, model.resolvedTheme.colorScheme)
        }
        .toolbar {
            ToolbarItemGroup(placement: .principal) {
                Picker("下载分类", selection: $model.downloadsCategory) {
                    ForEach(DownloadsCategory.allCases) { category in
                        Text(category.shortTitle).tag(category)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .fixedSize()
                .background {
                    GeometryReader { proxy in
                        Color.clear
                            .onAppear { categoryPickerWidth = proxy.size.width }
                            .onChange(of: proxy.size.width) { categoryPickerWidth = proxy.size.width }
                    }
                }
                .simultaneousGesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            // Native segmented controls already handle clicks;
                            // this only adds drag-across tracking. The system
                            // still draws the Liquid Glass selected segment.
                            guard abs(value.translation.width) > 3 else { return }
                            let items = DownloadsCategory.allCases
                            let segment = categoryPickerWidth / CGFloat(items.count)
                            let index = min(
                                max(Int(value.location.x / segment), 0),
                                items.count - 1
                            )
                            if model.downloadsCategory != items[index] {
                                withAnimation(.smooth(duration: 0.16)) {
                                    model.downloadsCategory = items[index]
                                }
                            }
                        }
                )
                .help("点击或拖动切换下载分类")

                if model.downloadsCategory == .game {
                    Picker("版本类型", selection: $model.downloadVersionChannel) {
                        ForEach(DownloadVersionChannel.allCases) { channel in
                            Text(channel.title).tag(channel)
                        }
                    }
                    .pickerStyle(.menu)
                    .labelsHidden()
                    .fixedSize()
                    .padding(.leading, 8)
                    .help("选择正式版、快照、愚人节或远古版本")
                } else if model.downloadsCategory == .mod || model.downloadsCategory == .modpack {
                    Picker("加载器", selection: $model.downloadContentLoader) {
                        ForEach(ContentLoaderFilter.allCases) { filter in
                            Text(filter.title).tag(filter)
                        }
                    }
                    .pickerStyle(.menu)
                    .labelsHidden()
                    .fixedSize()
                    .padding(.leading, 8)
                    .help("选择 Forge、NeoForge、Fabric、Quilt 或 OptiFine")
                }
            }
        }
    }

    private var middleColumn: some View {
        VStack(alignment: .leading, spacing: 0) {
            switch model.downloadsCategory {
            case .game:
                GameDownloadSection()
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            case .mod, .modpack:
                ScrollView {
                    ComingSoonCategory(
                        category: model.downloadsCategory,
                        loader: model.downloadContentLoader
                    )
                    .padding(.top, 48)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .fgScrollEdgeEffect()
            case .resourcepack, .shaderpack, .datapack:
                ScrollView {
                    ComingSoonCategory(category: model.downloadsCategory)
                        .padding(.top, 48)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .fgScrollEdgeEffect()
            }
        }
        .padding(.horizontal, FGLayout.contentPadding)
        .padding(.top, FGSpacing.lg)
        .padding(.bottom, FGLayout.contentPadding)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private var queueColumn: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: FGSpacing.page) {
                queueSection
                FGSeparator()
                historySection
            }
            .padding(.horizontal, FGLayout.contentPadding)
            .padding(.top, FGSpacing.lg)
            .padding(.bottom, FGLayout.contentPadding)
        }
        .background(FG.surface.opacity(0.30))
        .fgScrollEdgeEffect()
    }

    private var queueSection: some View {
        VStack(alignment: .leading, spacing: FGSpacing.xl) {
            HStack(spacing: FGSpacing.md) {
                Image(systemName: "arrow.down.circle")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(FG.accent)
                Text("任务队列")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(FG.textPrimary)
                Spacer(minLength: FGSpacing.md)
                Text(model.downloadsSpeedText)
                    .font(.system(size: 11.5))
                    .foregroundStyle(FG.textSecondary)
                    .monospacedDigit()
                    .lineLimit(1)
            }

            if model.activeDownloads.isEmpty {
                Text("没有进行中的任务")
                    .font(FGFont.caption)
                    .foregroundStyle(FG.textTertiary)
            } else {
                ForEach(model.activeDownloads) { row in
                    QueueRow(row: row)
                }
            }
        }
    }

    private var historySection: some View {
        VStack(alignment: .leading, spacing: FGSpacing.lg) {
            Text("最近完成")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(FG.textPrimary)
            Text("完成的安装会进入实例库，并可继续在「游戏设置」里调整内存与显示选项。")
                .font(FGFont.caption)
                .foregroundStyle(FG.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// 游戏 tab: version list on the left, detail + 安装 on the right.
struct GameDownloadSection: View {
    @EnvironmentObject private var model: AppModel

    private var filteredVersions: [CoreVersionEntry] {
        let query = model.downloadsQuery.trimmingCharacters(in: .whitespaces).lowercased()
        guard !query.isEmpty else { return model.downloadVersions }
        return model.downloadVersions.filter { $0.id.lowercased().contains(query) }
    }

    var body: some View {
        // The version list fills the remaining page height instead of stopping
        // at a fixed 300pt, while the detail pane keeps its natural height.
        HStack(alignment: .top, spacing: FGSpacing.lg) {
            versionPane
                .frame(width: 280, alignment: .topLeading)
                .frame(maxHeight: .infinity, alignment: .topLeading)
            detailPane
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private var versionPane: some View {
        VStack(alignment: .leading, spacing: FGSpacing.md) {
            TextField("搜索版本", text: $model.downloadsQuery)
                .textFieldStyle(.roundedBorder)
                .frame(maxWidth: .infinity)

            if let error = model.downloadVersionsError, model.downloadVersions.isEmpty {
                Text(error)
                    .font(FGFont.caption)
                    .foregroundStyle(FG.danger)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, FGSpacing.md)
            } else if model.downloadVersions.isEmpty {
                if model.isLoadingDownloadVersions {
                    HStack(spacing: FGSpacing.sm) {
                        ProgressView().controlSize(.small)
                        Text("版本清单加载中…")
                            .font(FGFont.caption)
                            .foregroundStyle(FG.textTertiary)
                    }
                    .padding(.top, FGSpacing.md)
                } else {
                    Text(model.downloadVersionChannel.emptyMessage)
                        .font(FGFont.caption)
                        .foregroundStyle(FG.textTertiary)
                        .padding(.top, FGSpacing.md)
                }
            } else if filteredVersions.isEmpty {
                Text("没有匹配的版本")
                    .font(FGFont.caption)
                    .foregroundStyle(FG.textTertiary)
                    .padding(.top, FGSpacing.md)
            } else {
                ScrollView {
                    VStack(spacing: 2) {
                        ForEach(filteredVersions.prefix(80)) { version in
                            VersionRow(
                                version: version,
                                isSelected: version.id == model.downloadSelectedVersionId
                            )
                        }
                    }
                }
                .frame(maxHeight: .infinity)
                .fgScrollEdgeEffect()
            }
        }
        .frame(maxHeight: .infinity, alignment: .topLeading)
    }

    private var detailPane: some View {
        GameDownloadDetail {
            model.openInstallSheet(for: model.downloadSelectedVersionId ?? "")
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

private struct VersionRow: View {
    let version: CoreVersionEntry
    let isSelected: Bool

    @EnvironmentObject private var model: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isHovering = false

    var body: some View {
        Button {
            model.downloadSelectedVersionId = version.id
        } label: {
            HStack(spacing: FGSpacing.md) {
                Image(systemName: "cube.fill")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(isSelected ? Color.white.opacity(0.9) : FG.textTertiary)
                Text(version.id)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(isSelected ? Color.white : FG.textPrimary)
                    .lineLimit(1)
                Spacer(minLength: FGSpacing.md)
                if let released = version.released {
                    Text(String(released.prefix(10)))
                        .font(FGFont.caption)
                        .foregroundStyle(isSelected ? Color.white.opacity(0.8) : FG.textTertiary)
                        .lineLimit(1)
                }
            }
            .padding(.horizontal, FGSpacing.ml)
            .frame(height: 36)
            .background(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(
                        isSelected
                            ? FG.accent
                            : (isHovering ? FG.glass.opacity(0.5) : Color.clear)
                    )
            )
            .contentShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        }
        .buttonStyle(.plain)
        .onHover { isHovering = $0 }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.12), value: isHovering)
    }
}

/// Detail panel of the game tab: version summary + 安装. Loader, loader version
/// and instance name are chosen in the modal install sheet, not inline.
private struct GameDownloadDetail: View {
    @EnvironmentObject private var model: AppModel
    let onDownload: () -> Void

    private var selectedVersion: CoreVersionEntry? {
        model.downloadVersions.first { $0.id == model.downloadSelectedVersionId }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: FGSpacing.page) {
            if let version = selectedVersion {
                summary(version)
                downloadButton
            } else {
                ContentUnavailableView(
                    "选择一个版本",
                    systemImage: "cube",
                    description: Text("在左侧选择一个 Minecraft 版本后即可安装。")
                )
                .frame(maxWidth: .infinity)
            }
        }
    }

    private func summary(_ version: CoreVersionEntry) -> some View {
        VStack(alignment: .leading, spacing: FGSpacing.md) {
            HStack(alignment: .firstTextBaseline, spacing: FGSpacing.md) {
                Text(version.id)
                    .font(FGFont.headingBold)
                    .foregroundStyle(FG.textPrimary)
                    .lineLimit(1)
                Text(kindText(version))
                    .font(FGFont.captionMedium)
                    .foregroundStyle(FG.accent)
                    .padding(.horizontal, FGSpacing.sm)
                    .padding(.vertical, FGSpacing.xxs)
                    .background(Capsule().fill(FG.accent.opacity(0.12)))
            }

            VStack(alignment: .leading, spacing: FGSpacing.sm) {
                if let plan = model.downloadPlan {
                    KeyValueRow(label: "需要 Java", value: "Java \(plan.javaMajor)")
                    KeyValueRow(label: "文件", value: "\(plan.files) 个")
                    KeyValueRow(label: "体积", value: InstanceFormat.byteSize(Int64(plan.totalBytes)))
                } else if let error = model.downloadPlanError {
                    Text("无法计算安装内容：\(error)")
                        .font(FGFont.caption)
                        .foregroundStyle(FG.danger)
                } else {
                    HStack(spacing: FGSpacing.sm) {
                        ProgressView().controlSize(.small)
                        Text("正在计算安装内容…")
                            .font(FGFont.caption)
                            .foregroundStyle(FG.textTertiary)
                    }
                }
                if let released = version.released {
                    KeyValueRow(label: "发布", value: String(released.prefix(10)))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func kindText(_ version: CoreVersionEntry) -> String {
        switch version.kind {
        case "release": "正式版"
        case "snapshot": "快照"
        default: version.kind
        }
    }

    private var downloadButton: some View {
        HStack(spacing: FGSpacing.md) {
            Button(action: onDownload) {
                Label("安装", systemImage: "arrow.down.circle")
            }
            .controlSize(.large)
            .tint(FG.accent)
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.capsule)
            .disabled(model.isGameDownloadRunning)

            if model.isGameDownloadRunning {
                ProgressView().controlSize(.small)
                Text("任务进行中…可在右侧队列查看进度")
                    .font(FGFont.caption)
                    .foregroundStyle(FG.textTertiary)
            }
        }
        .padding(.top, FGSpacing.sm)
    }
}

private struct ComingSoonCategory: View {
    let category: DownloadsCategory
    var loader: ContentLoaderFilter?

    var body: some View {
        ContentUnavailableView {
            Label(category.title, systemImage: category.symbol)
        } description: {
            Text(description)
        } actions: {
            MilestoneChip(text: "\(category.milestone ?? "后续") 接入")
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 48)
    }

    private var description: String {
        switch category {
        case .mod:
            if let loader, loader != .all {
                "筛选 \(loader.title) 模组；搜索、依赖解析与安装将在 M8 接入。"
            } else {
                "在 Modrinth 与 CurseForge 中搜索模组，解析依赖后安装到实例。"
            }
        case .modpack:
            if let loader, loader != .all {
                "筛选 \(loader.title) 整合包；导入与更新将在 M9 接入。"
            } else {
                "导入与更新 Modrinth、CurseForge、MultiMC 与 HMCL 整合包。"
            }
        case .resourcepack: "浏览资源包并按实例的 MC 版本过滤兼容性。"
        case .shaderpack: "为已安装光影加载器的实例选择光影包。"
        case .datapack: "把数据包放进存档或世界的 datapacks 目录。"
        case .game: ""
        }
    }
}

private struct QueueRow: View {
    let row: DownloadRow

    var body: some View {
        VStack(alignment: .leading, spacing: FGSpacing.xl) {
            HStack(spacing: FGSpacing.md) {
                Text(row.title)
                    .font(FGFont.small)
                    .foregroundStyle(FG.textPrimary)
                    .lineLimit(1)
                Spacer(minLength: FGSpacing.md)
                Text(row.stateDescription)
                    .font(.system(size: 11))
                    .foregroundStyle(FG.textTertiary)
                    .monospacedDigit()
                    .lineLimit(1)
            }
            ThinProgressBar(progress: row.fraction)
                .accessibilityLabel(row.title)
        }
    }
}

private extension DownloadRow {
    var stateDescription: String {
        switch state {
        case "succeeded": "已完成"
        case "failed": "失败"
        case "paused": "已暂停"
        case "cancelled": "已取消"
        default: "\(Int((fraction * 100).rounded()))%"
        }
    }
}
