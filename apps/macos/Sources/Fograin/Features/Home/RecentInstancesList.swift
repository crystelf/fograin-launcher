import FograinKit
import SwiftUI

private enum RecentFilter: String, CaseIterable, Identifiable {
    case recent
    case favorites
    case all

    var id: String { rawValue }

    var title: String {
        switch self {
        case .recent: "最近"
        case .favorites: "收藏"
        case .all: "全部"
        }
    }
}

struct RecentInstancesSection: View {
    let mode: HomeViewMode

    @EnvironmentObject private var model: AppModel
    @State private var filter: RecentFilter = .recent

    private var shownInstances: [Instance] {
        switch filter {
        case .recent: SampleData.recentInstances
        case .favorites: SampleData.favoriteInstances
        case .all: model.instances
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: FGSpacing.md) {
            HStack(alignment: .center, spacing: FGSpacing.md) {
                Text("实例")
                    .font(FGFont.subheadBold)
                    .foregroundStyle(FG.textPrimary)

                Spacer(minLength: FGSpacing.md)

                Picker("范围", selection: $filter) {
                    ForEach(RecentFilter.allCases) { option in
                        Text(option.title).tag(option)
                    }
                }
                .pickerStyle(.segmented)
                .controlSize(.small)
                .labelsHidden()
                .frame(width: 150)
                .help("筛选实例")
            }
            .frame(height: 25)

            switch mode {
            case .list:
                VStack(spacing: 2) {
                    ForEach(shownInstances) { instance in
                        HomeInstanceRow(
                            instance: instance,
                            isActive: instance.id == model.currentInstance.id
                        )
                    }
                }
            case .grid:
                LazyVGrid(
                    columns: [
                        GridItem(.adaptive(minimum: 240, maximum: 360), spacing: FGSpacing.lg),
                    ],
                    alignment: .leading,
                    spacing: FGSpacing.lg
                ) {
                    ForEach(shownInstances) { instance in
                        HomeInstanceCard(
                            instance: instance,
                            isActive: instance.id == model.currentInstance.id
                        )
                    }
                }
            }
        }
    }
}

private struct HomeInstanceRow: View {
    let instance: Instance
    let isActive: Bool

    @EnvironmentObject private var model: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isHovering = false

    var body: some View {
        HStack(spacing: FGSpacing.lg) {
            InstanceTile(instance: instance, size: 34)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: FGSpacing.xs) {
                    Text(instance.name)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(FG.textPrimary)
                        .lineLimit(1)
                    if instance.isFavorite {
                        Image(systemName: "star.fill")
                            .font(.system(size: 8))
                            .foregroundStyle(FG.warning)
                    }
                }
                Text(instance.homeDetail)
                    .font(.system(size: 11.5))
                    .foregroundStyle(FG.textTertiary)
                    .lineLimit(1)
            }

            Spacer(minLength: FGSpacing.md)

            Text(InstanceFormat.relativeDate(instance.lastPlayed))
                .font(FGFont.small)
                .foregroundStyle(FG.textTertiary)
                .lineLimit(1)

            Menu {
                instanceActions
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(FG.textTertiary)
                    .frame(width: 24, height: 24)
                    .contentShape(Rectangle())
            }
            .menuStyle(.borderlessButton)
            .menuIndicator(.hidden)
            .fixedSize()
            .help("更多操作")
            .accessibilityLabel("\(instance.name) 更多操作")
        }
        .padding(.horizontal, FGSpacing.ml)
        .frame(height: 52)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(
                    isActive
                        ? FG.accent.opacity(0.18)
                        : (isHovering ? FG.glass.opacity(0.5) : Color.clear)
                )
        )
        .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .gesture(
            TapGesture(count: 2)
                .onEnded { model.openInstance(instance) }
                .exclusively(
                    before: TapGesture(count: 1)
                        .onEnded { model.selectHomeInstance(instance) }
                )
        )
        .onHover { isHovering = $0 }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.12), value: isHovering)
        .contextMenu {
            instanceActions
        }
        .help("单击选中，双击进入实例库")
    }

    @ViewBuilder
    private var instanceActions: some View {
        Button("开始游戏") {
            model.startGame(instance)
        }
        Button("在实例库中显示") {
            model.openInstance(instance)
        }
        Divider()
        Button("打开实例文件夹") {
            model.openInstanceFolder(instance)
        }
        Button(instance.isFavorite ? "取消收藏" : "收藏") {
            model.toggleFavorite(instance)
        }
    }
}

private struct HomeInstanceCard: View {
    let instance: Instance
    let isActive: Bool

    @EnvironmentObject private var model: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isHovering = false

    var body: some View {
        HStack(spacing: FGSpacing.lg) {
            InstanceTile(instance: instance, size: 40)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: FGSpacing.xs) {
                    Text(instance.name)
                        .font(FGFont.bodyMedium)
                        .foregroundStyle(FG.textPrimary)
                        .lineLimit(1)
                    if instance.isFavorite {
                        Image(systemName: "star.fill")
                            .font(.system(size: 8))
                            .foregroundStyle(FG.warning)
                    }
                }
                Text(instance.displayVersion)
                    .font(FGFont.caption)
                    .foregroundStyle(FG.textTertiary)
                    .lineLimit(1)
            }

            Spacer(minLength: FGSpacing.md)

            Text(InstanceFormat.relativeDate(instance.lastPlayed))
                .font(FGFont.caption)
                .foregroundStyle(FG.textTertiary)
                .lineLimit(1)
        }
        .padding(FGSpacing.lg)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(
                    isActive
                        ? FG.accent.opacity(0.18)
                        : (isHovering ? FG.surfaceRaised : FG.surface)
                )
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(isActive ? FG.accent.opacity(0.5) : FG.separator, lineWidth: 1)
        )
        .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .gesture(
            TapGesture(count: 2)
                .onEnded { model.openInstance(instance) }
                .exclusively(
                    before: TapGesture(count: 1)
                        .onEnded { model.selectHomeInstance(instance) }
                )
        )
        .onHover { isHovering = $0 }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.12), value: isHovering)
        .contextMenu {
            Button("开始游戏") {
                model.startGame(instance)
            }
            Button("在实例库中显示") {
                model.openInstance(instance)
            }
        }
        .help("单击选中，双击进入实例库")
    }
}
