import FograinKit
import SwiftUI

/// Loader families offered by the install dialog. Only 原版 is installable in
/// M4; the others are reserved slots with placeholder version lists so the
/// flow and naming are settled before the installers land in M5.
enum GameLoaderKind: String, CaseIterable, Identifiable {
    case vanilla
    case fabric
    case fabricAPI
    case quilt
    case forge
    case neoforge
    case optifine
    case sqlAPI

    var id: String { rawValue }

    var title: String {
        switch self {
        case .vanilla: "原版"
        case .fabric: "Fabric"
        case .fabricAPI: "Fabric API"
        case .quilt: "Quilt"
        case .forge: "Forge"
        case .neoforge: "NeoForge"
        case .optifine: "OptiFine"
        case .sqlAPI: "SQL API"
        }
    }

    var subtitle: String {
        switch self {
        case .vanilla: "纯净游戏，不需要额外加载器"
        case .fabric: "轻量、更新快的模组加载器"
        case .fabricAPI: "多数 Fabric 模组的前置"
        case .quilt: "Fabric 的社区分支"
        case .forge: "老牌模组加载器"
        case .neoforge: "Forge 的现代分支"
        case .optifine: "光影与性能优化"
        case .sqlAPI: "预留的扩展接口"
        }
    }

    var symbol: String {
        switch self {
        case .vanilla: "cube"
        case .fabric, .fabricAPI: "puzzlepiece"
        case .quilt: "square.grid.2x2"
        case .forge: "hammer"
        case .neoforge: "flame"
        case .optifine: "sparkles"
        case .sqlAPI: "cylinder.split.1x2"
        }
    }

    var milestone: String? {
        self == .vanilla ? nil : "M5"
    }

    var isAvailable: Bool {
        self == .vanilla
    }

    /// Placeholder versions until fg-loader lands. Keeping them visible makes
    /// the eventual version picker a drop-in replacement.
    var placeholderVersions: [String] {
        switch self {
        case .vanilla: []
        case .fabric: ["0.16.9（最新）", "0.16.10", "0.15.11"]
        case .fabricAPI: ["0.116.0（最新）", "0.115.6"]
        case .quilt: ["0.27.0（最新）", "0.26.11"]
        case .forge: ["47.2.0（最新）", "47.3.0"]
        case .neoforge: ["21.1.72（最新）", "21.1.80"]
        case .optifine: ["HD U M5（最新）", "HD U I7"]
        case .sqlAPI: ["预留"]
        }
    }
}

/// Dialog shown after pressing 下载 on the game tab. It is intentionally the
/// only place that asks for loader / loader version / instance name; memory and
/// display options belong to 游戏设置 after the download.
struct GameInstallSheet: View {
    let initialVersionID: String

    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss

    @State private var selectedVersionID: String
    @State private var loader: GameLoaderKind = .vanilla
    @State private var loaderVersion = ""
    @State private var instanceName = ""
    @State private var isInstalling = false
    @State private var errorMessage: String?

    init(initialVersionID: String) {
        self.initialVersionID = initialVersionID
        _selectedVersionID = State(initialValue: initialVersionID)
        #if DEBUG
        if let raw = ProcessInfo.processInfo.environment["FOGRAIN_SNAPSHOT_LOADER"],
           let kind = GameLoaderKind(rawValue: raw) {
            _loader = State(initialValue: kind)
            _loaderVersion = State(initialValue: kind.placeholderVersions.first ?? "")
        }
        #endif
    }

    private var gameVersionOptions: [String] {
        var ids = model.downloadVersions.map(\.id)
        if !ids.contains(selectedVersionID) {
            ids.insert(selectedVersionID, at: 0)
        }
        return ids
    }

    private var canInstall: Bool {
        loader.isAvailable && !selectedVersionID.isEmpty && !isInstalling
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            FGSeparator()
            ScrollView {
                VStack(alignment: .leading, spacing: FGSpacing.page) {
                    gameVersionSection
                    loaderSection
                    if !loader.isAvailable {
                        loaderVersionSection
                    }
                    instanceNameSection
                    if let errorMessage {
                        errorBanner(errorMessage)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, FGSpacing.page)
                .padding(.vertical, FGSpacing.lg)
            }
            FGSeparator()
            footer
        }
        .frame(width: 620, height: 640)
        .background(FG.background)
    }

    private var header: some View {
        HStack(alignment: .center, spacing: FGSpacing.md) {
            VStack(alignment: .leading, spacing: 2) {
                Text("安装游戏")
                    .font(FGFont.headingBold)
                    .foregroundStyle(FG.textPrimary)
                Text("选择加载器与版本，然后下载")
                    .font(FGFont.caption)
                    .foregroundStyle(FG.textTertiary)
            }
            Spacer(minLength: FGSpacing.md)
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
            }
            .controlSize(.small)
            .fgCapsuleButton(circular: true)
            .help("关闭")
        }
        .padding(.horizontal, FGSpacing.page)
        .padding(.vertical, FGSpacing.lg)
    }

    private var gameVersionSection: some View {
        VStack(alignment: .leading, spacing: FGSpacing.sm) {
            Text("游戏版本")
                .font(FGFont.small)
                .foregroundStyle(FG.textSecondary)
            Picker("游戏版本", selection: $selectedVersionID) {
                ForEach(gameVersionOptions, id: \.self) { id in
                    Text(id).tag(id)
                }
            }
            .pickerStyle(.menu)
            .labelsHidden()
            .frame(width: 220, alignment: .leading)
        }
    }

    private var loaderSection: some View {
        VStack(alignment: .leading, spacing: FGSpacing.md) {
            HStack(alignment: .firstTextBaseline, spacing: FGSpacing.md) {
                Text("加载器")
                    .font(FGFont.subheadBold)
                    .foregroundStyle(FG.textPrimary)
                Spacer(minLength: 0)
                Text("原版可直接安装；其余为 M5 预留位")
                    .font(FGFont.caption)
                    .foregroundStyle(FG.textTertiary)
            }

            LazyVGrid(
                columns: [
                    GridItem(.flexible(), spacing: FGSpacing.md),
                    GridItem(.flexible(), spacing: FGSpacing.md),
                ],
                spacing: FGSpacing.md
            ) {
                ForEach(GameLoaderKind.allCases) { option in
                    loaderCard(option)
                }
            }
        }
    }

    private func loaderCard(_ option: GameLoaderKind) -> some View {
        let isSelected = loader == option
        return Button {
            loader = option
            loaderVersion = option.placeholderVersions.first ?? ""
            errorMessage = nil
        } label: {
            HStack(alignment: .top, spacing: FGSpacing.md) {
                Image(systemName: option.symbol)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(isSelected ? FG.accent : FG.textSecondary)
                    .frame(width: 22)

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: FGSpacing.xs) {
                        Text(option.title)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(FG.textPrimary)
                        if let milestone = option.milestone {
                            Text(milestone)
                                .font(.system(size: 9, weight: .semibold))
                                .foregroundStyle(FG.accent)
                        }
                    }
                    Text(option.subtitle)
                        .font(FGFont.caption)
                        .foregroundStyle(FG.textTertiary)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
            }
            .padding(FGSpacing.md)
            .frame(maxWidth: .infinity, minHeight: 62, alignment: .topLeading)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(isSelected ? FG.accent.opacity(0.13) : FG.surface)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(
                        isSelected ? FG.accent.opacity(0.55) : FG.separator,
                        lineWidth: 1
                    )
            )
            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private var loaderVersionSection: some View {
        VStack(alignment: .leading, spacing: FGSpacing.sm) {
            Text("\(loader.title) 版本")
                .font(FGFont.small)
                .foregroundStyle(FG.textSecondary)
            Picker("加载器版本", selection: $loaderVersion) {
                ForEach(loader.placeholderVersions, id: \.self) { version in
                    Text(version).tag(version)
                }
            }
            .pickerStyle(.menu)
            .labelsHidden()
            .frame(width: 220, alignment: .leading)

            Text("\(loader.title) 安装器将在 M5 接入；版本列表是预留位。当前请选择原版安装。")
                .font(FGFont.caption)
                .foregroundStyle(FG.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var instanceNameSection: some View {
        VStack(alignment: .leading, spacing: FGSpacing.sm) {
            Text("实例名称")
                .font(FGFont.small)
                .foregroundStyle(FG.textSecondary)
            TextField(selectedVersionID, text: $instanceName)
                .textFieldStyle(.roundedBorder)
                .frame(maxWidth: 320)
            Text("留空时使用版本号。内存、JVM 参数与显示选项在下载完成后的「游戏设置」里调整。")
                .font(FGFont.caption)
                .foregroundStyle(FG.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func errorBanner(_ message: String) -> some View {
        HStack(alignment: .top, spacing: FGSpacing.sm) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(FG.danger)
            Text(message)
                .font(FGFont.small)
                .foregroundStyle(FG.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(FGSpacing.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(FG.danger.opacity(0.10))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(FG.danger.opacity(0.30), lineWidth: 1)
        )
    }

    private var footer: some View {
        HStack(spacing: FGSpacing.lg) {
            Text(loader.isAvailable ? "将安装 \(selectedVersionID)（原版）" : "\(loader.title) 暂不可用")
                .font(FGFont.caption)
                .foregroundStyle(FG.textTertiary)

            Spacer(minLength: FGSpacing.md)

            Button("取消") {
                dismiss()
            }
            .keyboardShortcut(.cancelAction)

            Button {
                install()
            } label: {
                if isInstalling {
                    ProgressView().controlSize(.small)
                } else {
                    Text("安装")
                }
            }
            .controlSize(.large)
            .tint(FG.accent)
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.capsule)
            .keyboardShortcut(.defaultAction)
            .disabled(!canInstall)
            .help(loader.isAvailable ? "开始下载并安装" : "该加载器将在 M5 接入")
        }
        .padding(.horizontal, FGSpacing.page)
        .padding(.vertical, FGSpacing.lg)
    }

    private func install() {
        guard canInstall else { return }
        isInstalling = true
        errorMessage = nil
        Task { @MainActor in
            defer { isInstalling = false }
            do {
                try await model.startGameInstall(
                    version: selectedVersionID,
                    instanceName: instanceName
                )
                dismiss()
            } catch {
                if case let CoreBridgeError.rejected(payload) = error {
                    errorMessage = payload.userMessage
                } else {
                    errorMessage = (error as? CoreBridgeError)?.message ?? error.localizedDescription
                }
            }
        }
    }
}
