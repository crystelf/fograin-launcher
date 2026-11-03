import FograinKit
import SwiftUI

struct LibraryListView: View {
    let sections: [LibrarySection]

    @EnvironmentObject private var model: AppModel
    @State private var collapsedGroups: Set<LibraryGroup> = []

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            LibraryColumnHeader()

            FGSeparator()

            if sections.isEmpty {
                emptyState
            } else {
                ForEach(sections) { section in
                    LibraryGroupHeader(
                        group: section.group,
                        count: section.instances.count,
                        isCollapsed: collapsedGroups.contains(section.group)
                    ) {
                        toggle(section.group)
                    }
                    .padding(.top, 4)

                    if !collapsedGroups.contains(section.group) {
                        VStack(spacing: 2) {
                            ForEach(section.instances) { instance in
                                LibraryRow(
                                    instance: instance,
                                    isSelected: model.librarySelection == instance.id
                                )
                            }
                        }
                        .padding(.bottom, FGSpacing.md)
                    }
                }
            }
        }
    }

    private var emptyState: some View {
        ContentUnavailableView {
            Label("没有匹配的实例", systemImage: "shippingbox")
        } description: {
            Text("试试其他关键字。")
        } actions: {
            Button("清除搜索") {
                model.libraryQuery = ""
            }
            Button("新建实例") {
                model.newInstance()
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 80)
    }

    private func toggle(_ group: LibraryGroup) {
        if collapsedGroups.contains(group) {
            collapsedGroups.remove(group)
        } else {
            collapsedGroups.insert(group)
        }
    }
}

private struct LibraryColumnHeader: View {
    var body: some View {
        HStack(spacing: FGSpacing.lg) {
            Color.clear
                .frame(width: 30, height: 1)

            Text("名称")
                .frame(maxWidth: .infinity, alignment: .leading)

            Text("游戏版本")
                .frame(width: 80, alignment: .leading)

            Text("模组")
                .frame(width: 56, alignment: .leading)

            Text("大小")
                .frame(width: 64, alignment: .leading)

            Text("上次游玩")
                .frame(width: 72, alignment: .leading)

            Color.clear
                .frame(width: 16, height: 1)
        }
        .font(.system(size: 11, weight: .medium))
        .foregroundStyle(FG.textTertiary)
        .padding(.horizontal, FGSpacing.ml)
        .frame(height: 28)
    }
}

private struct LibraryGroupHeader: View {
    let group: LibraryGroup
    let count: Int
    let isCollapsed: Bool
    let toggle: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button(action: toggle) {
            HStack(spacing: FGSpacing.sm) {
                Image(systemName: "chevron.down")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(FG.textTertiary)
                    .rotationEffect(.degrees(isCollapsed ? -90 : 0))

                Text(group.title)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(FG.textSecondary)

                Text("\(count)")
                    .font(.system(size: 11))
                    .foregroundStyle(FG.textTertiary)

                Spacer(minLength: 0)
            }
            .padding(.horizontal, FGSpacing.ml)
            .frame(height: 31)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.15), value: isCollapsed)
        .accessibilityLabel("\(group.title) 分组，\(count) 个实例")
        .accessibilityHint(isCollapsed ? "展开" : "折叠")
    }
}

private struct LibraryRow: View {
    let instance: Instance
    let isSelected: Bool

    @EnvironmentObject private var model: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isHovering = false

    private var cellColor: Color {
        isSelected ? Color.white.opacity(0.86) : FG.textSecondary
    }

    var body: some View {
        Button {
            model.librarySelection = instance.id
        } label: {
            HStack(spacing: FGSpacing.lg) {
                LoaderTile(loader: instance.loader, size: 30)

                HStack(spacing: FGSpacing.sm) {
                    Text(instance.name)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(isSelected ? Color.white : FG.textPrimary)
                        .lineLimit(1)

                    if instance.isFavorite {
                        Image(systemName: "star.fill")
                            .font(.system(size: 9))
                            .foregroundStyle(isSelected ? Color.white : FG.warning)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Text(instance.minecraftVersion)
                    .frame(width: 80, alignment: .leading)

                Text(instance.modsText)
                    .frame(width: 56, alignment: .leading)

                Text(InstanceFormat.byteSize(instance.sizeBytes))
                    .frame(width: 64, alignment: .leading)

                Text(InstanceFormat.relativeDate(instance.lastPlayed))
                    .frame(width: 72, alignment: .leading)

                Menu {
                    rowActions
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(isSelected ? Color.white : FG.textTertiary)
                        .frame(width: 16, height: 20)
                        .contentShape(Rectangle())
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .fixedSize()
                .help("更多操作")
                .accessibilityLabel("\(instance.name) 更多操作")
            }
            .font(.system(size: 12))
            .foregroundStyle(cellColor)
            .monospacedDigit()
            .padding(.horizontal, FGSpacing.ml)
            .frame(height: 44)
            .background(
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .fill(
                        isSelected
                            ? FG.accent
                            : (isHovering ? FG.glass.opacity(0.4) : Color.clear)
                    )
            )
            .contentShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
        }
        .buttonStyle(.plain)
        .simultaneousGesture(
            TapGesture(count: 2).onEnded {
                model.manageInstance(instance)
            }
        )
        .onHover { isHovering = $0 }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.12), value: isHovering)
        .contextMenu {
            rowActions
        }
        .help("选择“\(instance.name)”，双击进入实例管理")
    }

    @ViewBuilder
    private var rowActions: some View {
        Button("开始游戏") {
            model.startGame(instance)
        }
        Button("在检查器中显示") {
            model.librarySelection = instance.id
        }
        Divider()
        Button("打开实例文件夹") {
            model.openInstanceFolder(instance)
        }
        Button(instance.isFavorite ? "取消收藏" : "收藏") {
            model.toggleFavorite(instance)
        }
        Divider()
        Button("管理实例…") {
            model.manageInstance(instance)
        }
    }
}
