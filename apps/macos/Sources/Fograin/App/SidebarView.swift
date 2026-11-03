import FograinKit
import SwiftUI

struct SidebarView: View {
    @EnvironmentObject private var model: AppModel
    @State private var isAccountMenuPresented = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            brand
            navigation

            Spacer(minLength: FGSpacing.section)

            accountSection
        }
        .padding(.horizontal, FGSpacing.ml)
        .padding(.top, FGSpacing.md)
        .padding(.bottom, FGSpacing.md)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background {
            FG.sidebar
                .ignoresSafeArea()
        }
    }

    private var brand: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("雾雨")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(FG.textPrimary)
            Text("Fograin Launcher")
                .font(FGFont.caption)
                .foregroundStyle(FG.textTertiary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, FGSpacing.ml)
        .padding(.top, FGSpacing.md)
        .padding(.bottom, FGSpacing.xl)
    }

    private var navigation: some View {
        VStack(alignment: .leading, spacing: FGSpacing.xs) {
            ForEach(SidebarGroup.allCases) { group in
                Text(group.title)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(FG.textTertiary)
                    .padding(.horizontal, FGSpacing.ml)
                    .padding(.top, FGSpacing.lg)
                    .padding(.bottom, FGSpacing.xs)
                    .frame(maxWidth: .infinity, alignment: .leading)

                ForEach(group.sections) { section in
                    SidebarNavRow(
                        section: section,
                        isActive: model.selection == section,
                        badge: badgeCount(for: section)
                    ) {
                        model.selection = section
                    }
                }
            }
        }
    }

    private func badgeCount(for section: AppSection) -> Int? {
        switch section {
        case .library: model.instances.count
        case .downloads: model.downloads.count
        default: nil
        }
    }

    private var accountSection: some View {
        VStack(spacing: 0) {
            FGSeparator()

            Button {
                isAccountMenuPresented = true
            } label: {
                AccountMenuLabel(account: model.account)
            }
            .buttonStyle(.plain)
            .frame(width: 220, height: 50)
            .contentShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
            .padding(.top, FGSpacing.xs)
            .popover(isPresented: $isAccountMenuPresented, arrowEdge: .bottom) {
                accountMenu
            }
            .accessibilityLabel("账户菜单，\(model.account.name)")
        }
    }

    private var accountMenu: some View {
        VStack(alignment: .leading, spacing: 1) {
            AccountMenuRow(title: "账户管理", systemImage: "person.crop.circle") {
                isAccountMenuPresented = false
                model.showNotice("账户管理将在 M7 接入", symbol: "person.crop.circle")
            }
            AccountMenuRow(title: "添加账户…", systemImage: "person.badge.plus") {
                isAccountMenuPresented = false
                model.showNotice("添加账户将在 M7 接入", symbol: "person.badge.plus")
            }

            Divider()
                .padding(.vertical, FGSpacing.xs)

            AccountMenuRow(title: "切换账户…", systemImage: "arrow.triangle.2.circlepath") {
                isAccountMenuPresented = false
                model.showNotice("多账户切换将在 M7 接入", symbol: "arrow.triangle.2.circlepath")
            }
            AccountMenuRow(title: "账户设置…", systemImage: "gearshape") {
                isAccountMenuPresented = false
                model.navigate(to: .accounts)
            }
        }
        .padding(FGSpacing.sm)
        .frame(width: 220)
    }

}

private struct SidebarNavRow: View {
    let section: AppSection
    let isActive: Bool
    let badge: Int?
    let action: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isHovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: FGSpacing.ml) {
                Image(systemName: section.symbol)
                    .font(.system(size: 16, weight: isActive ? .semibold : .regular))
                    .frame(width: 16)

                Text(section.title)
                    .font(.system(size: 13, weight: isActive ? .semibold : .regular))

                Spacer(minLength: FGSpacing.md)

                if let badge {
                    Text("\(badge)")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(
                            isActive
                                ? Color.white.opacity(0.9)
                                : (section == .downloads ? FG.accent : FG.textSecondary)
                        )
                        .padding(.horizontal, 7)
                        .padding(.vertical, 1)
                        .background(
                            Capsule().fill(isActive ? Color.white.opacity(0.18) : FG.glass)
                        )
                }
            }
            .foregroundStyle(isActive ? Color.white : FG.textPrimary)
            .padding(.horizontal, FGSpacing.ml)
            .frame(height: 32)
            .background(
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .fill(
                        isActive
                            ? FG.accent
                            : (isHovering ? FG.glass.opacity(0.65) : Color.clear)
                    )
            )
            .contentShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
        }
        .buttonStyle(.plain)
        .onHover { isHovering = $0 }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.12), value: isHovering)
        .accessibilityAddTraits(isActive ? .isSelected : [])
    }
}

private struct AccountMenuLabel: View {
    let account: Account

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isHovering = false

    var body: some View {
        HStack(spacing: FGSpacing.ml) {
            ZStack {
                LinearGradient(
                    colors: [
                        FograinGenerated.InstancePalette.moss.color,
                        FograinGenerated.InstancePalette.forest.color,
                    ],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                Text(account.initial)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white)
            }
            .frame(width: 30, height: 30)
            .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))

            VStack(alignment: .leading, spacing: 1) {
                Text(account.name)
                    .font(.system(size: 12.5, weight: .semibold))
                    .foregroundStyle(FG.textPrimary)
                    .lineLimit(1)
                HStack(spacing: FGSpacing.xs) {
                    Circle()
                        .fill(account.isOnline ? FG.success : FG.textTertiary)
                        .frame(width: 6, height: 6)
                    Text(account.kind)
                        .font(.system(size: 11))
                        .foregroundStyle(FG.textTertiary)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: FGSpacing.md)

            Image(systemName: "chevron.up.chevron.down")
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(FG.textTertiary)
        }
        .padding(.horizontal, FGSpacing.ml)
        .frame(width: 220, height: 50)
        .background(
            RoundedRectangle(cornerRadius: 9, style: .continuous)
                .fill(isHovering ? FG.glass.opacity(0.65) : Color.clear)
        )
        .contentShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
        .onHover { isHovering = $0 }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.12), value: isHovering)
    }
}


private struct AccountMenuRow: View {
    let title: String
    let systemImage: String
    let action: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isHovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: FGSpacing.md) {
                Image(systemName: systemImage)
                    .font(.system(size: 13, weight: .regular))
                    .frame(width: 16)
                Text(title)
                    .font(FGFont.small)
                Spacer(minLength: 0)
            }
            .foregroundStyle(FG.textPrimary)
            .padding(.horizontal, FGSpacing.md)
            .frame(height: 28)
            .background(
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(isHovering ? FG.glass : Color.clear)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { isHovering = $0 }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.1), value: isHovering)
    }
}
